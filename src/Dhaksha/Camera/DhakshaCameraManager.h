/****************************************************************************
 *
 * DhakshaGroundControl: owns the single active gimbal camera.
 *
 * The operator picks the vendor (SIYI, ViewPro, Skydroid) plus its IP and
 * RTSP URL once; the choice is stored and, on every start-up, the manager
 * creates the matching DhakshaGimbalCamera and points QGC's video stream at
 * the camera's RTSP URL so video starts automatically.
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

    static DhakshaCameraManager* instance();
    static QObject* qmlSingletonFactory(QQmlEngine*, QJSEngine*);

    QStringList             vendorNames () const;
    int                     vendor      () const { return _vendor; }
    QString                 cameraIp    () const { return _cameraIp; }
    QString                 rtspUrl     () const { return _rtspUrl; }
    DhakshaGimbalCamera*    camera      () const { return _camera; }

    /// Changing the vendor loads that vendor's saved IP / RTSP URL; call apply() to use them.
    void setVendor      (int vendor);
    void setCameraIp    (const QString& cameraIp);
    void setRtspUrl     (const QString& rtspUrl);

    /// Saves the settings, (re)creates the camera instance and starts its RTSP video stream.
    Q_INVOKABLE void apply();

    /// Restores the selected vendor's default IP and RTSP URL (not saved until apply()).
    Q_INVOKABLE void restoreVendorDefaults();

    static QString defaultCameraIp  (int vendor);
    static QString defaultRtspUrl   (int vendor);

signals:
    void vendorChanged  ();
    void cameraIpChanged();
    void rtspUrlChanged ();
    void cameraChanged  ();

private:
    explicit DhakshaCameraManager(QObject* parent = nullptr);

    void _loadVendorSettings    ();
    void _saveSettings          ();
    void _createCamera          ();
    void _startVideoStream      ();

    int                     _vendor     = VendorSkydroid;
    QString                 _cameraIp;
    QString                 _rtspUrl;
    DhakshaGimbalCamera*    _camera     = nullptr;
};
