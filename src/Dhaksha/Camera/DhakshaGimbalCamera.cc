/****************************************************************************
 *
 * DhakshaGroundControl: vendor-neutral gimbal camera interface.
 *
 ****************************************************************************/

#include "DhakshaGimbalCamera.h"

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
    }
}

void DhakshaGimbalCamera::_setRecording(bool recording)
{
    if (recording != _recording) {
        _recording = recording;
        emit recordingChanged();
    }
}
