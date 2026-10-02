/****************************************************************************
 *
 * DhakshaGroundControl: owns the single active gimbal camera.
 *
 * The operator picks the vendor (SIYI, ViewPro, Skydroid) plus its IP and
 * RTSP URL once; the choice is stored when applied and, on every start-up,
 * the manager creates the matching DhakshaGimbalCamera and points QGC's video
 * stream at the camera's RTSP URL so video starts automatically. Until a
 * camera has been applied the vendor is None and QGC's own video settings are
 * left untouched. Optionally the video
 * is also forwarded to a remote RTMP server.
 *
 ****************************************************************************/

#pragma once

#include "DhakshaGimbalCamera.h"

#include <QObject>
#include <QStringList>

class QQmlEngine;
class QJSEngine;

class DhakshaCameraManager : public QObject
{
    Q_OBJECT

public:
    enum Vendor {
        VendorNone = 0,
        VendorSkydroid,
        VendorSiyi,
        VendorViewPro,
        VendorCount
    };
    Q_ENUM(Vendor)

    Q_PROPERTY(QStringList          vendorNames READ vendorNames                    CONSTANT)
    Q_PROPERTY(int                  vendor      READ vendor     WRITE setVendor     NOTIFY vendorChanged)
    Q_PROPERTY(QString              cameraIp    READ cameraIp   WRITE setCameraIp   NOTIFY cameraIpChanged)
    Q_PROPERTY(QString              rtspUrl     READ rtspUrl    WRITE setRtspUrl    NOTIFY rtspUrlChanged)
    Q_PROPERTY(DhakshaGimbalCamera* camera      READ camera                         NOTIFY cameraChanged)
    Q_PROPERTY(bool     streamForwardEnabled    READ streamForwardEnabled   WRITE setStreamForwardEnabled   NOTIFY streamForwardEnabledChanged)
    Q_PROPERTY(QString  streamForwardUrl        READ streamForwardUrl       WRITE setStreamForwardUrl       NOTIFY streamForwardUrlChanged)

    static DhakshaCameraManager* instance();
    static QObject* qmlSingletonFactory(QQmlEngine*, QJSEngine*);

    QStringList             vendorNames () const;
    int                     vendor      () const { return _vendor; }
    QString                 cameraIp    () const { return _cameraIp; }
    QString                 rtspUrl     () const { return _rtspUrl; }
    DhakshaGimbalCamera*    camera      () const { return _camera; }
    bool                    streamForwardEnabled() const { return _streamForwardEnabled; }
    QString                 streamForwardUrl    () const { return _streamForwardUrl; }

    /// Changing the vendor loads that vendor's saved IP / RTSP URL; call apply() to use them.
    void setVendor      (int vendor);
    void setCameraIp    (const QString& cameraIp);
    void setRtspUrl     (const QString& rtspUrl);
    void setStreamForwardEnabled(bool enabled);
    void setStreamForwardUrl    (const QString& url);

    /// Saves the settings, (re)creates the camera instance, starts its RTSP video stream and the server forwarding.
    Q_INVOKABLE void apply();

    /// Start-up: same as apply() with the stored settings, without saving them.
    void start();

    /// Restores the selected vendor's default IP and RTSP URL (not saved until apply()).
    Q_INVOKABLE void restoreVendorDefaults();

    static QString defaultCameraIp  (int vendor);
    static QString defaultRtspUrl   (int vendor);

signals:
    void vendorChanged  ();
    void cameraIpChanged();
    void rtspUrlChanged ();
    void cameraChanged  ();
    void streamForwardEnabledChanged();
    void streamForwardUrlChanged    ();

private:
    explicit DhakshaCameraManager(QObject* parent = nullptr);

    void _loadVendorSettings    ();
    void _saveSettings          ();
    void _createCamera          ();
    void _startVideoStream      ();
    void _applyStreamForward    ();

    int                     _vendor     = VendorNone;
    QString                 _cameraIp;
    QString                 _rtspUrl;
    DhakshaGimbalCamera*    _camera     = nullptr;
    bool                    _streamForwardEnabled = false;
    QString                 _streamForwardUrl;
};
