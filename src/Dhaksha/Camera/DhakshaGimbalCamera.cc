/****************************************************************************
 *
 * DhakshaGroundControl: vendor-neutral gimbal camera interface.
 *
 ****************************************************************************/

#include "DhakshaGimbalCamera.h"

#include <QTime>

DhakshaGimbalCamera::DhakshaGimbalCamera(const QString& vendorName, const QString& rtspUrl, QObject* parent)
    : QObject       (parent)
    , _vendorName   (vendorName)
    , _rtspUrl      (rtspUrl)
{
}

void DhakshaGimbalCamera::_setConnected(bool connected)
{
    if (connected != _connected) {
        _connected = connected;
        emit connectedChanged();
        _logCommand(connected ? tr("Camera connected") : tr("Camera not responding"));
    }
}

void DhakshaGimbalCamera::_setRecording(bool recording)
{
    if (recording != _recording) {
        _recording = recording;
        emit recordingChanged();
        _logCommand(recording ? tr("Recording ON") : tr("Recording OFF"));
    }
}

void DhakshaGimbalCamera::_logCommand(const QString& entry)
{
    _commandLog.prepend(QTime::currentTime().toString(QStringLiteral("HH:mm:ss.zzz ")) + entry);
    while (_commandLog.size() > kMaxLogEntries) {
        _commandLog.removeLast();
    }
    emit commandLogChanged();
}

void DhakshaGimbalCamera::clearCommandLog()
{
    _commandLog.clear();
    emit commandLogChanged();
}
