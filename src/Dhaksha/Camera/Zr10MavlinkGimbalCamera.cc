/****************************************************************************
 *
 * DhakshaGroundControl: SIYI ZR10 controlled through the flight controller.
 *
 ****************************************************************************/

#include "Zr10MavlinkGimbalCamera.h"

#include "LinkInterface.h"
#include "MAVLinkProtocol.h"
#include "MultiVehicleManager.h"
#include "QGCApplication.h"
#include "QGCToolbox.h"

#include <cmath>
#include <limits>

QSet<Zr10MavlinkGimbalCamera*> Zr10MavlinkGimbalCamera::_liveCameras;

namespace {
    const float kNoValue = std::numeric_limits<float>::quiet_NaN();

    QString commandName(int command)
    {
        switch (command) {
        case 1000:  return QStringLiteral("GIMBAL_PITCHYAW");
        case 531:   return QStringLiteral("SET_CAMERA_ZOOM");
        case 2000:  return QStringLiteral("IMAGE_START_CAPTURE");
        case 203:   return QStringLiteral("DO_DIGICAM_CONTROL");
        case 2500:  return QStringLiteral("VIDEO_START_CAPTURE");
        case 2501:  return QStringLiteral("VIDEO_STOP_CAPTURE");
        default:    return QString::number(command);
        }
    }
}

Zr10MavlinkGimbalCamera::Zr10MavlinkGimbalCamera(const QString& rtspUrl, QObject* parent)
    : DhakshaGimbalCamera(QStringLiteral("ZR10"), rtspUrl, parent)
{
    _liveCameras.insert(this);

    _rateTimer.setInterval(kRateResendMs);
    connect(&_rateTimer, &QTimer::timeout, this, &Zr10MavlinkGimbalCamera::_resendRates);

    _connectedTimer.setInterval(1000);
    connect(&_connectedTimer, &QTimer::timeout, this, &Zr10MavlinkGimbalCamera::_checkConnected);
    _connectedTimer.start();

    MultiVehicleManager* vehicles = qgcApp()->toolbox()->multiVehicleManager();
    connect(vehicles, &MultiVehicleManager::activeVehicleChanged, this, &Zr10MavlinkGimbalCamera::_activeVehicleChanged);
    _activeVehicleChanged(vehicles->activeVehicle());
}

Zr10MavlinkGimbalCamera::~Zr10MavlinkGimbalCamera()
{
    _liveCameras.remove(this);
}

void Zr10MavlinkGimbalCamera::_activeVehicleChanged(Vehicle* vehicle)
{
    if (_activeVehicle) {
        disconnect(_activeVehicle.data(), &Vehicle::mavlinkMessageReceived, this, &Zr10MavlinkGimbalCamera::_mavlinkMessageReceived);
    }
    _activeVehicle = vehicle;
    _lastGimbalMessage.invalidate();
    if (_activeVehicle) {
        connect(_activeVehicle.data(), &Vehicle::mavlinkMessageReceived, this, &Zr10MavlinkGimbalCamera::_mavlinkMessageReceived);
    }
    _checkConnected();
}

void Zr10MavlinkGimbalCamera::_mavlinkMessageReceived(const mavlink_message_t& message)
{
    switch (message.msgid) {
    case kMsgMountStatus:
    case kMsgMountOrientation:
    case kMsgGimbalManagerStatus:
    case kMsgGimbalDeviceAttitude:
        _lastGimbalMessage.restart();
        break;
    default:
        break;
    }
}

void Zr10MavlinkGimbalCamera::_checkConnected()
{
    _setConnected(_vehicle() && _lastGimbalMessage.isValid() && _lastGimbalMessage.elapsed() < kConnectedTimeoutMs);
}

void Zr10MavlinkGimbalCamera::_sendCommandNow(int command, float param1, float param2, float param3, float param4,
                                              float param5, float param6, float param7)
{
    Vehicle* vehicle = _vehicle();
    if (!vehicle) {
        _logCommand(tr("TX %1 not sent: no vehicle connected").arg(commandName(command)));
        return;
    }
    SharedLinkInterfacePtr link = vehicle->vehicleLinkManager()->primaryLink().lock();
    if (!link) {
        return;
    }
    MAVLinkProtocol* mavlink = qgcApp()->toolbox()->mavlinkProtocol();
    mavlink_message_t message;
    mavlink_msg_command_long_pack_chan(static_cast<uint8_t>(mavlink->getSystemId()),
                                       static_cast<uint8_t>(mavlink->getComponentId()),
                                       link->mavlinkChannel(),
                                       &message,
                                       static_cast<uint8_t>(vehicle->id()),
                                       static_cast<uint8_t>(vehicle->defaultComponentId()),
                                       static_cast<uint16_t>(command),
                                       0,
                                       param1, param2, param3, param4, param5, param6, param7);
    vehicle->sendMessageOnLinkThreadSafe(link.get(), message);
}

void Zr10MavlinkGimbalCamera::_sendCommandChecked(int command, float param1, float param2, float param3, float param4,
                                                  float param5, float param6, float param7)
{
    Vehicle* vehicle = _vehicle();
    if (!vehicle) {
        _logCommand(tr("TX %1 not sent: no vehicle connected").arg(commandName(command)));
        emit notice(tr("Not connected to the drone"));
        return;
    }
    _logCommand(QStringLiteral("TX %1").arg(commandName(command)));
    vehicle->sendMavCommandWithHandler(&Zr10MavlinkGimbalCamera::_commandResultHandler, new PendingCommand{ this, command },
                                       vehicle->defaultComponentId(), static_cast<MAV_CMD>(command),
                                       param1, param2, param3, param4, param5, param6, param7);
}

void Zr10MavlinkGimbalCamera::_commandResultHandler(void* resultHandlerData, int /*compId*/, MAV_RESULT commandResult,
                                                    uint8_t /*progress*/, Vehicle::MavCmdResultFailureCode_t failureCode)
{
    PendingCommand* pending = static_cast<PendingCommand*>(resultHandlerData);
    if (_liveCameras.contains(pending->camera)) {
        pending->camera->_commandResult(pending->command, commandResult, failureCode);
    }
    delete pending;
}

void Zr10MavlinkGimbalCamera::_commandResult(int command, MAV_RESULT result, Vehicle::MavCmdResultFailureCode_t failureCode)
{
    const bool accepted = failureCode == Vehicle::MavCmdResultCommandResultOnly && result == MAV_RESULT_ACCEPTED;
    QString reason;
    if (failureCode == Vehicle::MavCmdResultFailureNoResponseToCommand) {
        reason = tr("no answer from the drone");
    } else if (failureCode == Vehicle::MavCmdResultFailureDuplicateCommand) {
        reason = tr("still waiting for the previous one");
    } else if (result == MAV_RESULT_UNSUPPORTED) {
        reason = tr("not supported by the flight controller");
    } else if (!accepted) {
        reason = tr("refused (%1)").arg(static_cast<int>(result));
    }
    _logCommand(QStringLiteral("RX %1 %2").arg(commandName(command), accepted ? QStringLiteral("accepted") : reason));

    switch (command) {
    case kCmdImageStartCapture:
        if (accepted) {
            emit notice(tr("Photo taken"));
        } else if (result == MAV_RESULT_UNSUPPORTED && failureCode == Vehicle::MavCmdResultCommandResultOnly) {
            // Older ArduPilot: the same photo with the original camera command
            _sendCommandChecked(kCmdDigicamControl, 0, 0, 0, 0, 1);
        } else {
            emit notice(tr("Photo not taken: %1").arg(reason));
        }
        break;
    case kCmdDigicamControl:
        emit notice(accepted ? tr("Photo taken") : tr("Photo not taken: %1").arg(reason));
        break;
    case kCmdVideoStartCapture:
        if (accepted) {
            _setRecording(true);
        } else {
            emit notice(tr("Recording did not start: %1").arg(reason));
        }
        break;
    case kCmdVideoStopCapture:
        if (accepted) {
            _setRecording(false);
        } else {
            emit notice(tr("Recording did not stop: %1").arg(reason));
        }
        break;
    case kCmdGimbalPitchYaw:
        if (!accepted) {
            emit notice(tr("Gimbal did not move: %1").arg(reason));
        }
        break;
    default:
        break;
    }
}

void Zr10MavlinkGimbalCamera::takePhoto()
{
    // camera id 0 (all), no interval, 1 picture
    _sendCommandChecked(kCmdImageStartCapture, 0, 0, 1, 0);
}

void Zr10MavlinkGimbalCamera::startRecording()
{
    _sendCommandChecked(kCmdVideoStartCapture, 0, 0);
}

void Zr10MavlinkGimbalCamera::stopRecording()
{
    _sendCommandChecked(kCmdVideoStopCapture, 0);
}

void Zr10MavlinkGimbalCamera::_sendZoom(int direction)
{
    _logCommand(QStringLiteral("TX %1 %2").arg(commandName(kCmdSetCameraZoom)).arg(direction));
    _sendCommandNow(kCmdSetCameraZoom, kZoomTypeContinuous, direction);
    if (direction == 0) {
        // A lost stop would leave the lens zooming, so it goes twice
        _sendCommandNow(kCmdSetCameraZoom, kZoomTypeContinuous, 0);
    }
}

void Zr10MavlinkGimbalCamera::_sendRates(float yawRate, float pitchRate)
{
    // Angles NaN = rate control; flags 0 = yaw follows the drone; gimbal device 0 = the first gimbal
    _sendCommandNow(kCmdGimbalPitchYaw, kNoValue, kNoValue, pitchRate, yawRate, 0, 0, 0);
}

void Zr10MavlinkGimbalCamera::_sendAngles(float pitchDeg, float yawDeg)
{
    _rateTimer.stop();
    _yawRate = _pitchRate = 0;
    _sendCommandChecked(kCmdGimbalPitchYaw, pitchDeg, yawDeg, kNoValue, kNoValue, 0, 0, 0);
}

void Zr10MavlinkGimbalCamera::gimbalSpeed(int yawPercent, int pitchPercent)
{
    _yawRate   = qBound(-100, yawPercent, 100) * kMaxRateDegPerSec / 100.0f;
    _pitchRate = qBound(-100, pitchPercent, 100) * kMaxRateDegPerSec / 100.0f;
    if (qFuzzyIsNull(_yawRate) && qFuzzyIsNull(_pitchRate)) {
        gimbalStop();
        return;
    }
    _sendRates(_yawRate, _pitchRate);
    _rateTimer.start();
}

void Zr10MavlinkGimbalCamera::_resendRates()
{
    _sendRates(_yawRate, _pitchRate);
}

void Zr10MavlinkGimbalCamera::gimbalStop()
{
    _rateTimer.stop();
    _yawRate = _pitchRate = 0;
    // A lost stop would leave the gimbal turning, so it goes twice
    _sendRates(0, 0);
    _sendRates(0, 0);
}

void Zr10MavlinkGimbalCamera::gimbalCenter()
{
    _sendAngles(0, 0);
}

void Zr10MavlinkGimbalCamera::gimbalDown()
{
    _sendAngles(-90, 0);
}
