/****************************************************************************
 *
 * DhakshaGroundControl: forwards the received camera video to an RTMP server.
 *
 * GstVideoReceiver hands every encoded H.264 sample of its stream to the
 * forwarder through an appsink (no re-encoding). The forwarder pushes them
 * through its own pipeline:
 *
 *   appsrc --> h264parse --> [metadata probe] --> h264parse --> flvmux --> rtmp2sink
 *
 * The probe inserts an H.264 SEI "user data unregistered" NAL unit (UUID
 * kSeiUuid, JSON payload with the vehicle position) into every frame, so
 * the coordinates travel inside the video and stay frame-accurate through
 * RTMP and any server that relays the stream.
 *
 * Running in a separate pipeline keeps the on-screen video independent of
 * the network: a server or internet failure only stops the forwarding,
 * which is retried every few seconds while video keeps arriving.
 *
 ****************************************************************************/

#pragma once

#include <QByteArray>
#include <QElapsedTimer>
#include <QJsonObject>
#include <QMutex>
#include <QObject>
#include <QString>
#include <QTimer>

#include <gst/gst.h>

class GstStreamForwarder : public QObject
{
    Q_OBJECT

public:
    explicit GstStreamForwarder(QObject* parent = nullptr);
    ~GstStreamForwarder() override;

    /// Empty url stops forwarding. Must be called on the object's thread.
    void setUrl(const QString& url);

    /// Called from the video receiver's streaming thread for every encoded sample.
    void pushSample(GstSample* sample);

    /// Restarts the timeline; call when the source stream (re)starts.
    void resetTimestamps();

    /// Telemetry embedded into every forwarded frame from now on. Thread safe.
    void setMetadata(const QJsonObject& metadata);

    /// Annex B SEI NAL unit (start code included) carrying payload as user data unregistered.
    static QByteArray buildSeiNal(const QByteArray& payload);

    /// Inserts sei in front of the first slice of an Annex B access unit (exposed for tests).
    static QByteArray insertBeforeFirstSlice(const QByteArray& accessUnit, const QByteArray& sei);

    static const QByteArray kSeiUuid;   ///< 16 bytes identifying DhakshaGroundControl telemetry

signals:
    /// active: video is being delivered to the server. message: readable state for the operator.
    void statusChanged(bool active, const QString& message);

private slots:
    void _startPipeline();
    void _handleError(const QString& message);
    void _checkDelivery();

private:
    void _stopPipeline();
    void _setStatus(bool active, const QString& message);

    GstBuffer* _addMetadata(GstBuffer* buffer);

    static GstBusSyncReply _onBusMessage(GstBus* bus, GstMessage* message, gpointer user_data);
    static GstPadProbeReturn _onMetadataProbe(GstPad* pad, GstPadProbeInfo* info, gpointer user_data);

    QString         _url;
    QMutex          _lock;              ///< Guards the members used from the streaming thread
    GstElement*     _pipeline   = nullptr;
    GstElement*     _appsrc     = nullptr;
    bool            _startQueued = false;
    bool            _sentFirstFrame = false;
    GstClockTime    _baseTime   = GST_CLOCK_TIME_NONE;
    QElapsedTimer   _retryTimer;
    QTimer          _statusTimer;
    guint64         _lastSentBytes = 0;

    QMutex          _metadataLock;
    QJsonObject     _metadata;
    quint64         _frameNumber = 0;

    bool            _active     = false;
    QString         _message;

    static constexpr qint64 kRetryIntervalMs    = 5000;
    static constexpr int    kStatusIntervalMs   = 2000;
};
