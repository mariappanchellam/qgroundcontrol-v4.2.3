/****************************************************************************
 *
 * DhakshaGroundControl: Skydroid gimbal camera (C10 family).
 *
 * Skydroid cameras speak the Topotek ASCII protocol over UDP (default
 * 192.168.144.108:5000). Fixed-length frames:
 *
 *   "#TP" <source> <address> <data length> <'w'|'r'> <3-char id> <2 hex data> <2 hex checksum>
 *
 * source 'U', address 'G' gimbal / 'D' system and image / 'M' lens, and the
 * checksum is the sum of all preceding bytes modulo 256, e.g. "#TPUG2wPTZ056F"
 * centres the gimbal.
 *
 ****************************************************************************/

#pragma once

#include "DhakshaGimbalCamera.h"

#include <QElapsedTimer>
#include <QHostAddress>
#include <QTimer>

class QUdpSocket;

class SkydroidGimbalCamera : public DhakshaGimbalCamera
{
    Q_OBJECT

public:
    SkydroidGimbalCamera(const QString& cameraIp, const QString& rtspUrl, QObject* parent = nullptr);

    static constexpr quint16 kControlPort = 5000;

    bool controlSupported() const override { return true; }

    void takePhoto          () override;
    void startRecording     () override;
    void stopRecording      () override;
    void toggleDayNight     () override;
    void setPipMode         (int mode) override;

    void zoomIn             () override;
    void zoomOut            () override;
    void zoomStop           () override;
    void focusIn            () override;
    void focusOut           () override;
    void focusStop          () override;
    void autoFocus          () override;

    void gimbalSpeed        (int yawPercent, int pitchPercent) override;
    void gimbalStop         () override;
    void gimbalCenter       () override;
    void gimbalLock         () override;
    void gimbalFollow       () override;

    /// Builds a fixed-length Topotek frame including checksum (exposed for tests).
    static QByteArray buildFrame(char address, bool write, const char* id, quint8 data);

private slots:
    void _readBytes         ();
    void _pollStatus        ();
    void _resendGimbalSpeed ();

private:
    void _send              (char address, bool write, const char* id, quint8 data);
    void _sendGimbalSpeed   ();
    void _handleFrame       (const QByteArray& id, const QByteArray& data);

    static int _percentToSpeed(int percent);

    QUdpSocket*     _socket         = nullptr;
    QHostAddress    _cameraAddress;
    QTimer          _pollTimer;
    QTimer          _gimbalTimer;
    QElapsedTimer   _lastReceive;
    QByteArray      _rxBuffer;
    int             _yawSpeed       = 0;    ///< -99..99
    int             _pitchSpeed     = 0;    ///< -99..99

    static constexpr char kSource           = 'U';
    static constexpr char kAddressGimbal    = 'G';
    static constexpr char kAddressImage     = 'D';
    static constexpr char kAddressLens      = 'M';

    static constexpr int kPollIntervalMs        = 2000;
    static constexpr int kGimbalResendMs        = 300;
    static constexpr int kConnectionTimeoutMs   = 5000;
};
