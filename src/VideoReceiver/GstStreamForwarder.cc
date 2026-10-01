/****************************************************************************
 *
 * DhakshaGroundControl: forwards the received camera video to an RTMP server.
 *
 ****************************************************************************/

#include "GstStreamForwarder.h"
#include "QGCLoggingCategory.h"

#include <QMetaObject>
#include <QMutexLocker>

#include <gst/app/gstappsrc.h>

QGC_LOGGING_CATEGORY(StreamForwarderLog, "StreamForwarderLog")

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
    }

    if (_url.isEmpty()) {
        _setStatus(false, tr("Off"));
    } else {
        _setStatus(false, tr("Waiting for video"));
    }
}

void GstStreamForwarder::resetTimestamps()
{
    QMutexLocker locker(&_lock);
    _baseTime = GST_CLOCK_TIME_NONE;
}

void GstStreamForwarder::pushSample(GstSample* sample)
{
    QMutexLocker locker(&_lock);

    if (_appsrc == nullptr) {
        // Not running: (re)start from the Qt thread once the retry interval has passed
        if (!_startQueued && (!_retryTimer.isValid() || _retryTimer.elapsed() >= kRetryIntervalMs)) {
            _startQueued = true;
            QMetaObject::invokeMethod(this, "_startPipeline", Qt::QueuedConnection);
        }
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

    GstCaps* caps = gst_sample_get_caps(sample);
    if (caps != nullptr) {
        gst_app_src_set_caps(GST_APP_SRC(_appsrc), caps);
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
        if (_pipeline != nullptr || _url.isEmpty()) {
            return;
        }
        _retryTimer.restart();
    }

    const QString description = QStringLiteral(
        "appsrc name=src is-live=true format=time do-timestamp=false max-bytes=4000000 "
        "! queue leaky=downstream max-size-buffers=0 max-size-bytes=0 max-size-time=2000000000 "
        "! h264parse config-interval=-1 "
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

    return GST_BUS_DROP;
}
