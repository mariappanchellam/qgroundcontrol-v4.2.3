/****************************************************************************
 *
 * DhakshaGroundControl: owns the single active gimbal camera.
 *
 ****************************************************************************/

#include "DhakshaCameraManager.h"
#include "SkydroidGimbalCamera.h"

#ifndef DHAKSHA_CAMERA_NO_QGC
#include "QGCApplication.h"
#include "QGCToolbox.h"
#include "SettingsManager.h"
#include "VideoManager.h"
#include "VideoSettings.h"
#endif

#include <QQmlEngine>
#include <QSettings>

namespace {
    const char* kSettingsGroup  = "DhakshaCamera";
    const char* kConfiguredKey  = "configured";     // set once the operator applied a camera
    const char* kVendorKey      = "vendor";
    const char* kIpKey          = "ip";
    const char* kRtspKey        = "rtspUrl";
    const char* kForwardKey     = "streamForwardEnabled";
    const char* kForwardUrlKey  = "streamForwardUrl";

    QString vendorKey(int vendor)
    {
        return QStringLiteral("vendor%1").arg(vendor);
    }
}

DhakshaCameraManager* DhakshaCameraManager::instance()
{
    static DhakshaCameraManager* manager = new DhakshaCameraManager();
    return manager;
}

QObject* DhakshaCameraManager::qmlSingletonFactory(QQmlEngine*, QJSEngine*)
{
    DhakshaCameraManager* manager = instance();
    QQmlEngine::setObjectOwnership(manager, QQmlEngine::CppOwnership);
    return manager;
}

DhakshaCameraManager::DhakshaCameraManager(QObject* parent)
    : QObject(parent)
{
    QSettings settings;
    settings.beginGroup(kSettingsGroup);
    // Builds before kConfiguredKey saved the default vendor at every start-up, so only a
    // vendor the operator applied is trusted; otherwise leave QGC's video settings alone
    if (settings.value(kConfiguredKey, false).toBool()) {
        _vendor = qBound(0, settings.value(kVendorKey, VendorNone).toInt(), VendorCount - 1);
    }
    _streamForwardEnabled = settings.value(kForwardKey, false).toBool();
    _streamForwardUrl = settings.value(kForwardUrlKey).toString();
    settings.endGroup();
    _loadVendorSettings();
}

QStringList DhakshaCameraManager::vendorNames() const
{
    return { tr("None"), QStringLiteral("Skydroid"), QStringLiteral("SIYI"), QStringLiteral("ViewPro") };
}

QString DhakshaCameraManager::defaultCameraIp(int vendor)
{
    switch (vendor) {
    case VendorSkydroid:    return QStringLiteral("192.168.144.108");
    case VendorSiyi:        return QStringLiteral("192.168.144.25");
    default:                return QString();
    }
}

QString DhakshaCameraManager::defaultRtspUrl(int vendor)
{
    switch (vendor) {
    case VendorSkydroid:    return QStringLiteral("rtsp://192.168.144.108:554/stream=0");
    case VendorSiyi:        return QStringLiteral("rtsp://192.168.144.25:8554/main.264");
    default:                return QString();
    }
}

void DhakshaCameraManager::_loadVendorSettings()
{
    QSettings settings;
    settings.beginGroup(kSettingsGroup);
    settings.beginGroup(vendorKey(_vendor));
    setCameraIp(settings.value(kIpKey, defaultCameraIp(_vendor)).toString());
    setRtspUrl(settings.value(kRtspKey, defaultRtspUrl(_vendor)).toString());
}

void DhakshaCameraManager::_saveSettings()
{
    QSettings settings;
    settings.beginGroup(kSettingsGroup);
    settings.setValue(kConfiguredKey, true);
    settings.setValue(kVendorKey, _vendor);
    settings.setValue(kForwardKey, _streamForwardEnabled);
    settings.setValue(kForwardUrlKey, _streamForwardUrl);
    settings.beginGroup(vendorKey(_vendor));
    settings.setValue(kIpKey, _cameraIp);
    settings.setValue(kRtspKey, _rtspUrl);
}

void DhakshaCameraManager::setVendor(int vendor)
{
    vendor = qBound(0, vendor, VendorCount - 1);
    if (vendor != _vendor) {
        _vendor = vendor;
        emit vendorChanged();
        _loadVendorSettings();
    }
}

void DhakshaCameraManager::setCameraIp(const QString& cameraIp)
{
    const QString trimmed = cameraIp.trimmed();
    if (trimmed != _cameraIp) {
        _cameraIp = trimmed;
        emit cameraIpChanged();
    }
}

void DhakshaCameraManager::setRtspUrl(const QString& rtspUrl)
{
    const QString trimmed = rtspUrl.trimmed();
    if (trimmed != _rtspUrl) {
        _rtspUrl = trimmed;
        emit rtspUrlChanged();
    }
}

void DhakshaCameraManager::setStreamForwardEnabled(bool enabled)
{
    if (enabled != _streamForwardEnabled) {
        _streamForwardEnabled = enabled;
        emit streamForwardEnabledChanged();
    }
}

void DhakshaCameraManager::setStreamForwardUrl(const QString& url)
{
    const QString trimmed = url.trimmed();
    if (trimmed != _streamForwardUrl) {
        _streamForwardUrl = trimmed;
        emit streamForwardUrlChanged();
    }
}

void DhakshaCameraManager::restoreVendorDefaults()
{
    setCameraIp(defaultCameraIp(_vendor));
    setRtspUrl(defaultRtspUrl(_vendor));
}

void DhakshaCameraManager::apply()
{
    _saveSettings();
    start();
}

void DhakshaCameraManager::start()
{
    _createCamera();
    _startVideoStream();
    _applyStreamForward();
}

void DhakshaCameraManager::_createCamera()
{
    if (_camera) {
        _camera->deleteLater();
        _camera = nullptr;
    }

    switch (_vendor) {
    case VendorSkydroid:
        _camera = new SkydroidGimbalCamera(_cameraIp, _rtspUrl, this);
        break;
    case VendorSiyi:
        _camera = new DhakshaGimbalCamera(QStringLiteral("SIYI"), _rtspUrl, this);         // video stream only for now
        break;
    case VendorViewPro:
        _camera = new DhakshaGimbalCamera(QStringLiteral("ViewPro"), _rtspUrl, this);      // video stream only for now
        break;
    default:
        break;
    }
    emit cameraChanged();
}

void DhakshaCameraManager::_startVideoStream()
{
#ifndef DHAKSHA_CAMERA_NO_QGC
    if (_vendor == VendorNone || _rtspUrl.isEmpty()) {
        return;
    }
    VideoSettings* videoSettings = qgcApp()->toolbox()->settingsManager()->videoSettings();
    videoSettings->rtspUrl()->setRawValue(_rtspUrl);
    videoSettings->videoSource()->setRawValue(VideoSettings::videoSourceRTSP);
#endif
}

void DhakshaCameraManager::_applyStreamForward()
{
#ifndef DHAKSHA_CAMERA_NO_QGC
    VideoManager* videoManager = qgcApp()->toolbox()->videoManager();
    if (videoManager) {
        videoManager->setStreamForwardUrl(_streamForwardEnabled ? _streamForwardUrl : QString());
    }
#endif
}
