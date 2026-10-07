/****************************************************************************
 *
 * DhakshaGroundControl: forwards the received camera video to an RTMP server.
 *
 ****************************************************************************/

#include "GstStreamForwarder.h"
#include "QGCLoggingCategory.h"

#include <QDateTime>
#include <QJsonDocument>
#include <QMetaObject>
#include <QMutexLocker>

#include <gst/app/gstappsink.h>
#include <gst/app/gstappsrc.h>

QGC_LOGGING_CATEGORY(StreamForwarderLog, "StreamForwarderLog")

const QByteArray GstStreamForwarder::kSeiUuid("DHAKSHA-GCS-TLM1", 16);

GstStreamForwarder::GstStreamForwarder(QObject* parent)
    : QObject(parent)
{
    _statusTimer.setInterval(kStatusIntervalMs);
    connect(&_statusTimer, &QTimer::timeout, this, &GstStreamForwarder::_checkDelivery);
}

GstStreamForwarder::~GstStreamForwarder()
{
    _stopPipeline();
}

void GstStreamForwarder::setUrl(const QString& url)
{
    const QString trimmed = url.trimmed();
    if (trimmed == _url) {
        return;
    }
    _stopPipeline();
    _url = trimmed;
    {
        QMutexLocker locker(&_lock);
        _retryTimer.invalidate();
        _codecReported = false;
    }

    if (_url.isEmpty()) {
        _setStatus(false, tr("Off"));
    } else {
        _setStatus(false, tr("Waiting for video"));
    }
}

void GstStreamForwarder::shutdown()
{
    {
        QMutexLocker locker(&_lock);
        _closing = true;
    }
    _stopPipeline();
}

GstFlowReturn GstStreamForwarder::onNewSample(GstElement* appsink, gpointer user_data)
{
    GstSample* sample = gst_app_sink_pull_sample(GST_APP_SINK(appsink));
    if (sample != nullptr) {
        static_cast<GstStreamForwarder*>(user_data)->pushSample(sample);
        gst_sample_unref(sample);
    }
    return GST_FLOW_OK;
}

void GstStreamForwarder::resetTimestamps()
{
    QMutexLocker locker(&_lock);
    _baseTime = GST_CLOCK_TIME_NONE;
    _codecReported = false;
}

void GstStreamForwarder::setMetadata(const QJsonObject& metadata)
{
    QMutexLocker locker(&_metadataLock);
    _metadata = metadata;
}

QByteArray GstStreamForwarder::buildSeiNal(const QByteArray& payload)
{
    // SEI message: payloadType 5 (user data unregistered), payloadSize, UUID, data
    QByteArray rbsp;
    rbsp.append(char(5));
    int size = kSeiUuid.size() + payload.size();
    while (size >= 255) {
        rbsp.append(char(0xFF));
        size -= 255;
    }
    rbsp.append(char(size));
    rbsp.append(kSeiUuid);
    rbsp.append(payload);
    rbsp.append(char(0x80));                        // rbsp_trailing_bits

    QByteArray nal("\x00\x00\x00\x01\x06", 5);   // start code, nal_unit_type 6 (SEI)
    int zeros = 0;
    for (const char c : rbsp) {
        const quint8 byte = static_cast<quint8>(c);
        if (zeros >= 2 && byte <= 3) {
            nal.append(char(0x03));                 // emulation prevention
            zeros = 0;
        }
        nal.append(c);
        zeros = byte == 0 ? zeros + 1 : 0;
    }
    return nal;
}

QByteArray GstStreamForwarder::insertBeforeFirstSlice(const QByteArray& accessUnit, const QByteArray& sei)
{
    const int size = accessUnit.size();
    for (int i = 0; i + 3 < size; i++) {
        if (accessUnit.at(i) == 0 && accessUnit.at(i + 1) == 0 && accessUnit.at(i + 2) == 1) {
            const int nalType = accessUnit.at(i + 3) & 0x1F;
            if (nalType == 1 || nalType == 5) {
                const int start = (i > 0 && accessUnit.at(i - 1) == 0) ? i - 1 : i;
                return accessUnit.left(start) + sei + accessUnit.mid(start);
            }
            i += 2;
        }
    }
    return accessUnit;      // no slice found: leave the buffer untouched
}

GstBuffer* GstStreamForwarder::_addMetadata(GstBuffer* buffer)
{
    QJsonObject metadata;
    {
        QMutexLocker locker(&_metadataLock);
        metadata = _metadata;
    }
    metadata[QStringLiteral("frame")] = static_cast<qint64>(_frameNumber++);
    metadata[QStringLiteral("frame_ts")] = QDateTime::currentMSecsSinceEpoch();
    if (GST_BUFFER_PTS_IS_VALID(buffer)) {
        metadata[QStringLiteral("pts_ms")] = static_cast<qint64>(GST_BUFFER_PTS(buffer) / GST_MSECOND);
    }
    const QByteArray sei = buildSeiNal(QJsonDocument(metadata).toJson(QJsonDocument::Compact));

    GstMapInfo map;
    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        return buffer;
    }
    const QByteArray withSei = insertBeforeFirstSlice(QByteArray::fromRawData(reinterpret_cast<const char*>(map.data), static_cast<int>(map.size)), sei);
    gst_buffer_unmap(buffer, &map);

    GstBuffer* output = gst_buffer_new_allocate(nullptr, static_cast<gsize>(withSei.size()), nullptr);
    if (output == nullptr) {
        return buffer;
    }
    gst_buffer_fill(output, 0, withSei.constData(), static_cast<gsize>(withSei.size()));
    gst_buffer_copy_into(output, buffer, GST_BUFFER_COPY_METADATA, 0, static_cast<gsize>(-1));
    gst_buffer_unref(buffer);
    return output;
}

GstPadProbeReturn GstStreamForwarder::_onMetadataProbe(GstPad* /*pad*/, GstPadProbeInfo* info, gpointer user_data)
{
    GstBuffer* buffer = GST_PAD_PROBE_INFO_BUFFER(info);
    if (buffer != nullptr) {
        GST_PAD_PROBE_INFO_DATA(info) = static_cast<GstStreamForwarder*>(user_data)->_addMetadata(buffer);
    }
    return GST_PAD_PROBE_OK;
}

void GstStreamForwarder::pushSample(GstSample* sample)
{
    QMutexLocker locker(&_lock);

    if (_closing) {
        return;
    }

    GstCaps* caps = gst_sample_get_caps(sample);
    if (caps == nullptr || gst_caps_is_empty(caps)) {
        return;
    }
    const GstStructure* structure = gst_caps_get_structure(caps, 0);

    // The RTMP pipeline (h264parse, flvmux) only carries H.264; never start it for another codec
    if (!gst_structure_has_name(structure, "video/x-h264")) {
        if (!_codecReported) {
            _codecReported = true;
            QMetaObject::invokeMethod(this, "_reportUnsupportedCodec", Qt::QueuedConnection,
                                      Q_ARG(QString, QString::fromUtf8(gst_structure_get_name(structure))));
        }
        return;
    }

    if (_appsrc == nullptr) {
        // Not running: (re)start from the Qt thread once the retry interval has passed
        if (!_startQueued && (!_retryTimer.isValid() || _retryTimer.elapsed() >= kRetryIntervalMs)) {
            _startQueued = true;
            QMetaObject::invokeMethod(this, "_startPipeline", Qt::QueuedConnection);
        }
        return;
    }

    // Right after the stream starts the parser can announce AVC before it has seen SPS/PPS;
    // such caps cannot be negotiated, so wait for caps that carry codec_data
    if (g_strcmp0(gst_structure_get_string(structure, "stream-format"), "avc") == 0 && !gst_structure_has_field(structure, "codec_data")) {
        return;
    }

    GstBuffer* buffer = gst_sample_get_buffer(sample);
    if (buffer == nullptr) {
        return;
    }

    // RTMP players need a keyframe to start decoding
    if (!_sentFirstFrame && GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT)) {
        return;
    }

    // Shift the receiver's timeline so the RTMP stream starts at zero
    const GstClockTime pts = GST_BUFFER_PTS(buffer);
    const GstClockTime dts = GST_BUFFER_DTS(buffer);
    const GstClockTime reference = GST_CLOCK_TIME_IS_VALID(dts) ? dts : pts;
    if (!GST_CLOCK_TIME_IS_VALID(reference)) {
        return;
    }
    if (!GST_CLOCK_TIME_IS_VALID(_baseTime) || reference < _baseTime) {
        _baseTime = reference;
    }

    GstBuffer* copy = gst_buffer_copy(buffer);     // shares the memory, only the metadata is copied
    if (GST_CLOCK_TIME_IS_VALID(pts)) {
        GST_BUFFER_PTS(copy) = pts >= _baseTime ? pts - _baseTime : 0;
    }
    if (GST_CLOCK_TIME_IS_VALID(dts)) {
        GST_BUFFER_DTS(copy) = dts >= _baseTime ? dts - _baseTime : 0;
    }

    GstCaps* currentCaps = gst_app_src_get_caps(GST_APP_SRC(_appsrc));
    if (currentCaps == nullptr || !gst_caps_is_equal(currentCaps, caps)) {
        gst_app_src_set_caps(GST_APP_SRC(_appsrc), caps);
    }
    if (currentCaps != nullptr) {
        gst_caps_unref(currentCaps);
    }

    if (gst_app_src_push_buffer(GST_APP_SRC(_appsrc), copy) == GST_FLOW_OK) {
        _sentFirstFrame = true;
    }
}

void GstStreamForwarder::_startPipeline()
{
    {
        QMutexLocker locker(&_lock);
        _startQueued = false;
        if (_closing || _pipeline != nullptr || _url.isEmpty()) {
            return;
        }
        _retryTimer.restart();
    }

    const QString description = QStringLiteral(
        "appsrc name=src is-live=true format=time do-timestamp=false max-bytes=4000000 "
        "! queue leaky=downstream max-size-buffers=0 max-size-bytes=0 max-size-time=2000000000 "
        "! h264parse config-interval=-1 "
        "! capsfilter name=meta caps=\"video/x-h264,stream-format=byte-stream,alignment=au\" "
        "! h264parse "
        "! flvmux streamable=true "
        "! rtmp2sink name=sink sync=false async=false");

    GError* error = nullptr;
    GstElement* pipeline = gst_parse_launch(description.toUtf8().constData(), &error);
    if (pipeline == nullptr || error != nullptr) {
        const QString reason = error != nullptr ? QString::fromUtf8(error->message) : QString();
        if (error != nullptr) {
            g_error_free(error);
        }
        if (pipeline != nullptr) {
            gst_object_unref(pipeline);
        }
        qCWarning(StreamForwarderLog) << "Unable to create RTMP pipeline:" << reason;
        _setStatus(false, tr("RTMP streaming is not available in this build (%1)").arg(reason));
        return;
    }

    GstElement* meta = gst_bin_get_by_name(GST_BIN(pipeline), "meta");
    GstPad* metaPad = gst_element_get_static_pad(meta, "src");
    gst_pad_add_probe(metaPad, GST_PAD_PROBE_TYPE_BUFFER, _onMetadataProbe, this, nullptr);
    gst_object_unref(metaPad);
    gst_object_unref(meta);
    _frameNumber = 0;

    GstElement* sink = gst_bin_get_by_name(GST_BIN(pipeline), "sink");
    g_object_set(sink, "location", _url.toUtf8().constData(), nullptr);
    gst_object_unref(sink);

    GstBus* bus = gst_pipeline_get_bus(GST_PIPELINE(pipeline));
    gst_bus_set_sync_handler(bus, _onBusMessage, this, nullptr);
    gst_object_unref(bus);

    if (gst_element_set_state(pipeline, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) {
        gst_element_set_state(pipeline, GST_STATE_NULL);
        gst_object_unref(pipeline);
        _setStatus(false, tr("Cannot start streaming, retrying"));
        return;
    }

    qCDebug(StreamForwarderLog) << "Forwarding video to" << _url;
    _setStatus(false, tr("Connecting to %1").arg(_url));

    _lastSentBytes = 0;
    _statusTimer.start();

    QMutexLocker locker(&_lock);
    _pipeline = pipeline;
    _appsrc = gst_bin_get_by_name(GST_BIN(pipeline), "src");
    _sentFirstFrame = false;
}

void GstStreamForwarder::_stopPipeline()
{
    _statusTimer.stop();

    GstElement* pipeline = nullptr;
    GstElement* appsrc = nullptr;
    {
        QMutexLocker locker(&_lock);
        pipeline = _pipeline;
        appsrc = _appsrc;
        _pipeline = nullptr;
        _appsrc = nullptr;
    }

    if (appsrc != nullptr) {
        gst_object_unref(appsrc);
    }
    if (pipeline != nullptr) {
        GstBus* bus = gst_pipeline_get_bus(GST_PIPELINE(pipeline));
        gst_bus_set_sync_handler(bus, nullptr, nullptr, nullptr);
        gst_object_unref(bus);
        gst_element_set_state(pipeline, GST_STATE_NULL);
        gst_object_unref(pipeline);
    }
}

void GstStreamForwarder::_handleError(const QString& message)
{
    if (_pipeline == nullptr) {
        return;
    }
    qCWarning(StreamForwarderLog) << "RTMP forwarding error:" << message;
    _stopPipeline();
    {
        QMutexLocker locker(&_lock);
        _retryTimer.restart();
    }
    _setStatus(false, tr("Error: %1 (retrying)").arg(message));
}

void GstStreamForwarder::_reportUnsupportedCodec(const QString& mediaType)
{
    _stopPipeline();
    const QString codec = mediaType == QLatin1String("video/x-h265") ? QStringLiteral("H.265") : mediaType;
    qCWarning(StreamForwarderLog) << "Not forwarding" << mediaType << "video, RTMP forwarding needs H.264";
    _setStatus(false, tr("Not streaming: the camera sends %1 video, server streaming needs H.264. Set the camera's encoding to H.264.").arg(codec));
}

void GstStreamForwarder::_checkDelivery()
{
    if (_pipeline == nullptr) {
        return;
    }

    // rtmp2sink "stats" holds the bytes written to the server; growth means the stream is live
    guint64 sentBytes = 0;
    GstElement* sink = gst_bin_get_by_name(GST_BIN(_pipeline), "sink");
    if (sink != nullptr) {
        if (g_object_class_find_property(G_OBJECT_GET_CLASS(sink), "stats") != nullptr) {
            GstStructure* stats = nullptr;
            g_object_get(sink, "stats", &stats, nullptr);
            if (stats != nullptr) {
                gst_structure_get_uint64(stats, "out-bytes-total", &sentBytes);
                gst_structure_free(stats);
            }
        }
        gst_object_unref(sink);
    }

    if (sentBytes > _lastSentBytes) {
        _setStatus(true, tr("Streaming to %1 (%2 kbit/s)").arg(_url).arg((sentBytes - _lastSentBytes) * 8 / 1000 / (kStatusIntervalMs / 1000)));
    } else {
        _setStatus(false, _sentFirstFrame ? tr("No data reaching the server") : tr("Connecting to %1").arg(_url));
    }
    _lastSentBytes = sentBytes;
}

void GstStreamForwarder::_setStatus(bool active, const QString& message)
{
    if (active != _active || message != _message) {
        _active = active;
        _message = message;
        emit statusChanged(_active, _message);
    }
}

GstBusSyncReply GstStreamForwarder::_onBusMessage(GstBus* /*bus*/, GstMessage* message, gpointer user_data)
{
    GstStreamForwarder* self = static_cast<GstStreamForwarder*>(user_data);

    switch (GST_MESSAGE_TYPE(message)) {
    case GST_MESSAGE_ERROR: {
        GError* error = nullptr;
        gst_message_parse_error(message, &error, nullptr);
        const QString text = error != nullptr ? QString::fromUtf8(error->message) : QStringLiteral("unknown error");
        if (error != nullptr) {
            g_error_free(error);
        }
        QMetaObject::invokeMethod(self, "_handleError", Qt::QueuedConnection, Q_ARG(QString, text));
        break;
    }
    case GST_MESSAGE_EOS:
        QMetaObject::invokeMethod(self, "_handleError", Qt::QueuedConnection, Q_ARG(QString, QStringLiteral("server closed the stream")));
        break;
    default:
        break;
    }

    // A sync handler that drops a message owns it
    gst_message_unref(message);
    return GST_BUS_DROP;
}
