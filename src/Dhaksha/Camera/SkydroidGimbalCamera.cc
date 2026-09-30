/****************************************************************************
 *
 * DhakshaGroundControl: Skydroid gimbal camera (C10 family).
 *
 ****************************************************************************/

#include "SkydroidGimbalCamera.h"

#include <QUdpSocket>
#include <QtGlobal>

namespace {
    // Command ids of the Topotek protocol used by Skydroid cameras
    const char* kIdGimbalMode   = "PTZ";    // 00 stop, 05 home/centre, 06 lock, 07 follow
    const char* kIdYawSpeed     = "GSY";    // signed speed -99..99, positive = right
    const char* kIdPitchSpeed   = "GSP";    // signed speed -99..99, positive = up
    const char* kIdRecord       = "REC";    // 00 stop, 01 start
    const char* kIdCapture      = "CAP";    // 01 take picture
    const char* kIdZoom         = "ZMC";    // 00 stop, 01 out, 02 in
    const char* kIdFocus        = "FCC";    // 00 stop, 01 near, 02 far, 10 auto
    const char* kIdDayNight     = "IRC";    // 0A toggle
    const char* kIdPip          = "PIP";    // 00..03

    char hexChar(quint8 nibble)
    {
        return "0123456789ABCDEF"[nibble & 0x0F];
    }
}

SkydroidGimbalCamera::SkydroidGimbalCamera(const QString& cameraIp, const QString& rtspUrl, QObject* parent)
    : DhakshaGimbalCamera   (QStringLiteral("Skydroid"), rtspUrl, parent)
    , _socket               (new QUdpSocket(this))
    , _cameraAddress        (cameraIp)
{
    _socket->bind(QHostAddress(QHostAddress::AnyIPv4), 0);
    connect(_socket, &QUdpSocket::readyRead, this, &SkydroidGimbalCamera::_readBytes);

    _pollTimer.setInterval(kPollIntervalMs);
    connect(&_pollTimer, &QTimer::timeout, this, &SkydroidGimbalCamera::_pollStatus);
    _pollTimer.start();

    _gimbalTimer.setInterval(kGimbalResendMs);
    connect(&_gimbalTimer, &QTimer::timeout, this, &SkydroidGimbalCamera::_resendGimbalSpeed);

    _pollStatus();
}

QByteArray SkydroidGimbalCamera::buildFrame(char address, bool write, const char* id, quint8 data)
{
    QByteArray frame("#TP");
    frame.append(kSource);
    frame.append(address);
    frame.append('2');                      // fixed-length frames carry two data characters
    frame.append(write ? 'w' : 'r');
    frame.append(id, 3);
    frame.append(hexChar(data >> 4));
    frame.append(hexChar(data));

    quint8 checksum = 0;
    for (const char c : frame) {
        checksum += static_cast<quint8>(c);
    }
    frame.append(hexChar(checksum >> 4));
    frame.append(hexChar(checksum));
    return frame;
}

void SkydroidGimbalCamera::_send(char address, bool write, const char* id, quint8 data, const QString& description)
{
    const QByteArray frame = buildFrame(address, write, id, data);
    const bool sent = !_cameraAddress.isNull() && _socket->writeDatagram(frame, _cameraAddress, kControlPort) == frame.size();

    if (!description.isEmpty()) {
        _logCommand(QStringLiteral("%1 %2  %3").arg(sent ? QStringLiteral("TX") : tr("TX FAILED"), QString::fromLatin1(frame), description));
    }
}

// ---------------------------------------------------------------------------- Camera

void SkydroidGimbalCamera::takePhoto()
{
    _send(kAddressImage, true, kIdCapture, 0x01, tr("Take photo"));
}

void SkydroidGimbalCamera::startRecording()
{
    _send(kAddressImage, true, kIdRecord, 0x01, tr("Start recording"));
    _setRecording(true);    // confirmed or corrected by the next status poll
}

void SkydroidGimbalCamera::stopRecording()
{
    _send(kAddressImage, true, kIdRecord, 0x00, tr("Stop recording"));
    _setRecording(false);
}

void SkydroidGimbalCamera::toggleDayNight()
{
    _send(kAddressImage, true, kIdDayNight, 0x0A, tr("Day / night toggle"));
}

void SkydroidGimbalCamera::setPipMode(int mode)
{
    const int pipMode = qBound(0, mode, 3);
    _send(kAddressImage, true, kIdPip, static_cast<quint8>(pipMode), tr("PIP mode %1").arg(pipMode));
}

// ---------------------------------------------------------------------------- Lens

void SkydroidGimbalCamera::zoomIn()     { _send(kAddressLens, true, kIdZoom, 0x02, tr("Zoom in")); }
void SkydroidGimbalCamera::zoomOut()    { _send(kAddressLens, true, kIdZoom, 0x01, tr("Zoom out")); }
void SkydroidGimbalCamera::zoomStop()   { _send(kAddressLens, true, kIdZoom, 0x00, tr("Zoom stop")); }
void SkydroidGimbalCamera::focusIn()    { _send(kAddressLens, true, kIdFocus, 0x01, tr("Focus near")); }
void SkydroidGimbalCamera::focusOut()   { _send(kAddressLens, true, kIdFocus, 0x02, tr("Focus far")); }
void SkydroidGimbalCamera::focusStop()  { _send(kAddressLens, true, kIdFocus, 0x00, tr("Focus stop")); }
void SkydroidGimbalCamera::autoFocus()  { _send(kAddressLens, true, kIdFocus, 0x10, tr("Auto focus")); }

// ---------------------------------------------------------------------------- Gimbal

int SkydroidGimbalCamera::_percentToSpeed(int percent)
{
    return qBound(-99, (qBound(-100, percent, 100) * 99) / 100, 99);
}

void SkydroidGimbalCamera::gimbalSpeed(int yawPercent, int pitchPercent)
{
    _yawSpeed   = _percentToSpeed(yawPercent);
    _pitchSpeed = _percentToSpeed(pitchPercent);

    if (_yawSpeed == 0 && _pitchSpeed == 0) {
        gimbalStop();
        return;
    }
    _sendGimbalSpeed(true);
    _gimbalTimer.start();
}

void SkydroidGimbalCamera::_sendGimbalSpeed(bool log)
{
    _send(kAddressGimbal, true, kIdYawSpeed,   static_cast<quint8>(static_cast<qint8>(_yawSpeed)),   log ? tr("Gimbal yaw speed %1").arg(_yawSpeed) : QString());
    _send(kAddressGimbal, true, kIdPitchSpeed, static_cast<quint8>(static_cast<qint8>(_pitchSpeed)), log ? tr("Gimbal pitch speed %1").arg(_pitchSpeed) : QString());
}

void SkydroidGimbalCamera::_resendGimbalSpeed()
{
    _sendGimbalSpeed(false);
}

void SkydroidGimbalCamera::gimbalStop()
{
    _gimbalTimer.stop();
    _yawSpeed   = 0;
    _pitchSpeed = 0;
    _sendGimbalSpeed(true);
    _send(kAddressGimbal, true, kIdGimbalMode, 0x00, tr("Gimbal stop"));
}

void SkydroidGimbalCamera::gimbalCenter()
{
    _gimbalTimer.stop();
    _send(kAddressGimbal, true, kIdGimbalMode, 0x05, tr("Gimbal centre"));
}

void SkydroidGimbalCamera::gimbalLock()
{
    _send(kAddressGimbal, true, kIdGimbalMode, 0x06, tr("Gimbal lock"));
}

void SkydroidGimbalCamera::gimbalFollow()
{
    _send(kAddressGimbal, true, kIdGimbalMode, 0x07, tr("Gimbal follow"));
}

// ---------------------------------------------------------------------------- Status

void SkydroidGimbalCamera::_pollStatus()
{
    // Recording status query doubles as a heartbeat: any valid reply marks the camera connected
    _send(kAddressImage, false, kIdRecord, 0x00);

    if (connected() && (!_lastReceive.isValid() || _lastReceive.elapsed() > kConnectionTimeoutMs)) {
        _setConnected(false);
    }
}

void SkydroidGimbalCamera::_readBytes()
{
    while (_socket->hasPendingDatagrams()) {
        QByteArray datagram;
        datagram.resize(static_cast<int>(_socket->pendingDatagramSize()));
        _socket->readDatagram(datagram.data(), datagram.size());
        _rxBuffer.append(datagram);
    }

    // Frame: "#TP" src dst len rw id(3) data(len) crc(2)
    static const int kHeaderLength = 10;
    while (true) {
        const int start = _rxBuffer.indexOf("#TP");
        if (start < 0) {
            _rxBuffer.clear();
            return;
        }
        _rxBuffer.remove(0, start);
        if (_rxBuffer.size() < kHeaderLength) {
            return;
        }

        bool ok = false;
        const int dataLength = QByteArray(1, _rxBuffer.at(5)).toInt(&ok, 16);
        if (!ok) {
            _rxBuffer.remove(0, 3);
            continue;
        }
        const int frameLength = kHeaderLength + dataLength + 2;
        if (_rxBuffer.size() < frameLength) {
            return;
        }

        quint8 checksum = 0;
        for (int i = 0; i < frameLength - 2; i++) {
            checksum += static_cast<quint8>(_rxBuffer.at(i));
        }
        const QByteArray expected = QByteArray(1, hexChar(checksum >> 4)) + hexChar(checksum);
        if (_rxBuffer.mid(frameLength - 2, 2).toUpper() == expected) {
            _lastReceive.restart();
            _setConnected(true);
            _handleFrame(_rxBuffer.mid(7, 3), _rxBuffer.mid(kHeaderLength, dataLength));
        }
        _rxBuffer.remove(0, frameLength);
    }
}

void SkydroidGimbalCamera::_handleFrame(const QByteArray& id, const QByteArray& data)
{
    if (id == kIdRecord && data.size() >= 2) {
        bool ok = false;
        const int value = data.left(2).toInt(&ok, 16);
        if (ok) {
            _setRecording(value == 1);
        }
    }
}
