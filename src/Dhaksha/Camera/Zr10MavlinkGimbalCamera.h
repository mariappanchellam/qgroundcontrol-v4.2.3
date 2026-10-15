/****************************************************************************
 *
 * DhakshaGroundControl: SIYI ZR10 controlled through the flight controller.
 *
 * The ZR10 is wired to the autopilot (ArduPilot: MNT1_TYPE = 8 SIYI,
 * CAM1_TYPE = 4 Mount), so the camera controls are sent as standard MAVLink
 * commands to the active vehicle, which passes them to the gimbal:
 *
 *   gimbal turn / centre / down     MAV_CMD_DO_GIMBAL_MANAGER_PITCHYAW
 *   zoom in / out / stop            MAV_CMD_SET_CAMERA_ZOOM (continuous)
 *   photo                           MAV_CMD_IMAGE_START_CAPTURE (MAV_CMD_DO_DIGICAM_CONTROL if unsupported)
 *   start / stop recording          MAV_CMD_VIDEO_START_CAPTURE / MAV_CMD_VIDEO_STOP_CAPTURE
 *
 * The video still comes straight from the camera's RTSP stream. The ZR10 has
 * no night camera and no laser rangefinder.
 *
 ****************************************************************************/

#pragma once

#include "DhakshaGimbalCamera.h"

#include <QElapsedTimer>
#include <QPointer>
#include <QSet>
#include <QTimer>

#include "QGCMAVLink.h"
#include "Vehicle.h"

class Zr10MavlinkGimbalCamera : public DhakshaGimbalCamera
{
    Q_OBJECT

public:
    explicit Zr10MavlinkGimbalCamera(const QString& rtspUrl, QObject* parent = nullptr);
    ~Zr10MavlinkGimbalCamera() override;

    bool controlSupported() const override { return true; }

    void takePhoto      () override;
    void startRecording () override;
    void stopRecording  () override;

    void zoomIn         () override { _sendZoom(1); }
    void zoomOut        () override { _sendZoom(-1); }
    void zoomStop       () override { _sendZoom(0); }

    void gimbalSpeed    (int yawPercent, int pitchPercent) override;
    void gimbalStop     () override;
    void gimbalCenter   () override;
    void gimbalDown     () override;

private slots:
    void _activeVehicleChanged  (Vehicle* vehicle);
    void _mavlinkMessageReceived(const mavlink_message_t& message);
    void _resendRates           ();
    void _checkConnected        ();

private:
    // Command ids, written out so the build does not depend on the MAVLink headers' version
    static constexpr int kCmdGimbalPitchYaw     = 1000;    ///< MAV_CMD_DO_GIMBAL_MANAGER_PITCHYAW
    static constexpr int kCmdSetCameraZoom      = 531;     ///< MAV_CMD_SET_CAMERA_ZOOM
    static constexpr int kCmdImageStartCapture  = 2000;    ///< MAV_CMD_IMAGE_START_CAPTURE
    static constexpr int kCmdDigicamControl     = 203;     ///< MAV_CMD_DO_DIGICAM_CONTROL (older ArduPilot)
    static constexpr int kCmdVideoStartCapture  = 2500;    ///< MAV_CMD_VIDEO_START_CAPTURE
    static constexpr int kCmdVideoStopCapture   = 2501;    ///< MAV_CMD_VIDEO_STOP_CAPTURE
    static constexpr int kZoomTypeContinuous    = 1;       ///< ZOOM_TYPE_CONTINUOUS

    // Gimbal status messages that show the ZR10 is answering through the autopilot
    static constexpr uint32_t kMsgMountStatus           = 158;  ///< MOUNT_STATUS
    static constexpr uint32_t kMsgMountOrientation      = 265;  ///< MOUNT_ORIENTATION
    static constexpr uint32_t kMsgGimbalManagerStatus   = 281;  ///< GIMBAL_MANAGER_STATUS
    static constexpr uint32_t kMsgGimbalDeviceAttitude  = 285;  ///< GIMBAL_DEVICE_ATTITUDE_STATUS

    static constexpr float  kMaxRateDegPerSec   = 60.0f;   ///< Gimbal speed at full joystick
    static constexpr int    kRateResendMs       = 200;     ///< Rates are repeated while held, in case one is lost
    static constexpr int    kConnectedTimeoutMs = 3000;

    /// COMMAND_LONG without waiting for its answer: rates and zoom are sent often, and a start must never
    /// be held back waiting for the answer to the previous one
    void _sendCommandNow    (int command, float param1, float param2 = 0, float param3 = 0, float param4 = 0,
                             float param5 = 0, float param6 = 0, float param7 = 0);
    /// COMMAND_LONG with QGC's retries; the answer arrives in _commandResult
    void _sendCommandChecked(int command, float param1, float param2 = 0, float param3 = 0, float param4 = 0,
                             float param5 = 0, float param6 = 0, float param7 = 0);
    void _sendRates         (float yawRate, float pitchRate);
    void _sendAngles        (float pitchDeg, float yawDeg);
    void _sendZoom          (int direction);
    void _commandResult     (int command, MAV_RESULT result, Vehicle::MavCmdResultFailureCode_t failureCode);
    Vehicle* _vehicle       () const { return _activeVehicle.data(); }

    /// Passed with each checked command, so the answer can be matched to its command; freed by the handler
    struct PendingCommand {
        Zr10MavlinkGimbalCamera* camera;
        int                      command;
    };
    static void _commandResultHandler(void* resultHandlerData, int compId, MAV_RESULT commandResult, uint8_t progress,
                                      Vehicle::MavCmdResultFailureCode_t failureCode);
    /// Cameras still alive, so an answer arriving after the camera was replaced is ignored
    static QSet<Zr10MavlinkGimbalCamera*> _liveCameras;

    QPointer<Vehicle>   _activeVehicle;
    QTimer              _rateTimer;
    QTimer              _connectedTimer;
    QElapsedTimer       _lastGimbalMessage;
    float               _yawRate    = 0;
    float               _pitchRate  = 0;
};
