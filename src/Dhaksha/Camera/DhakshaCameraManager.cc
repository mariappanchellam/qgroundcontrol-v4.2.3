/****************************************************************************
 *
 * DhakshaGroundControl: owns the single active gimbal camera.
 *
 ****************************************************************************/

#include "DhakshaCameraManager.h"
#include "SiyiGimbalCamera.h"
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
    const char* kNightRtspKey   = "nightRtspUrl";
    const char* kVideoModeKey   = "videoMode";
    // Read by GstVideoReceiver when it opens the RTSP stream
    const char* kRtspLatencyKey = "DhakshaCamera/rtspLatencyMs";
    const char* kRtspTcpKey     = "DhakshaCamera/rtspTcp";
    // Day address saved by builds before the Skydroid's real one (port 554 stream=1) was known
    const char* kOldSkydroidRtsp = "rtsp://192.168.144.108:554/stream=0";
    const char* kForwardKey     = "streamForwardEnabled";
    const char* kForwardUrlKey  = "streamForwardUrl";
    const char* kGimbalSpeedKey = "DhakshaCamera/gimbalMaxSpeed";
    const char* kPipCameraKey   = "pipCamera";

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
    // The fleet flies the Skydroid camera, so a fresh install (or one whose settings were wiped by
    // reinstalling) starts with it; a vendor the operator applied is used as saved
    _vendor = VendorSkydroid;
    if (settings.value(kConfiguredKey, false).toBool()) {
        // Only Skydroid and the ZR10 remain; a camera type an earlier build saved (ViewPro, ZR10 over MAVLink) becomes Skydroid
        const int saved = settings.value(kVendorKey, VendorSkydroid).toInt();
        _vendor = saved == VendorZr10 ? VendorZr10 : VendorSkydroid;
    }
    // DhakshaKey sends the video to the server from Termux now, so forwarding always starts off,
    // whatever an earlier build saved; forwarding inside the app as well could crash it
    _streamForwardEnabled = false;
    settings.setValue(kForwardKey, false);
    _streamForwardUrl = settings.value(kForwardUrlKey).toString();
    _pipCamera = settings.value(kPipCameraKey, true).toBool();
    settings.endGroup();
    _loadVendorSettings();
}

QStringList DhakshaCameraManager::vendorNames() const
{
    return { tr("None"), QStringLiteral("Skydroid"), QStringLiteral("ZR10") };
}

QString DhakshaCameraManager::defaultCameraIp(int vendor)
{
    switch (vendor) {
    case VendorSkydroid:    return QStringLiteral("192.168.144.108");
    case VendorZr10:        return QStringLiteral("192.168.144.25");
    default:                return QString();
    }
}

QString DhakshaCameraManager::defaultRtspUrl(int vendor)
{
    switch (vendor) {
    case VendorSkydroid:    return QStringLiteral("rtsp://192.168.144.108:554/stream=1");
    case VendorZr10:        return QStringLiteral("rtsp://192.168.144.25:8554/main.264");
    default:                return QString();
    }
}

QString DhakshaCameraManager::defaultNightRtspUrl(int vendor)
{
    switch (vendor) {
    case VendorSkydroid:    return QStringLiteral("rtsp://192.168.144.108:555/stream=2");
    default:                return QString();
    }
}

void DhakshaCameraManager::_loadVendorSettings()
{
    QSettings settings;
    settings.beginGroup(kSettingsGroup);
    settings.beginGroup(vendorKey(_vendor));
    setCameraIp(settings.value(kIpKey, defaultCameraIp(_vendor)).toString());
    QString rtspUrl = settings.value(kRtspKey, defaultRtspUrl(_vendor)).toString();
    if (_vendor == VendorSkydroid && rtspUrl == QLatin1String(kOldSkydroidRtsp)) {
        rtspUrl = defaultRtspUrl(_vendor);
    }
    setRtspUrl(rtspUrl);
    setNightRtspUrl(settings.value(kNightRtspKey, defaultNightRtspUrl(_vendor)).toString());
    setVideoMode(settings.value(kVideoModeKey, defaultVideoMode(_vendor)).toInt());
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
    settings.setValue(kNightRtspKey, _nightRtspUrl);
    settings.setValue(kVideoModeKey, _videoMode);
}

void DhakshaCameraManager::setVendor(int vendor)
{
    vendor = qBound(0, vendor, VendorCount - 1);
    if (vendor != _vendor) {
        _vendor = vendor;
        emit vendorChanged();
        emit pipAvailableChanged();
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
        emit pipAvailableChanged();
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

void DhakshaCameraManager::setNightRtspUrl(const QString& rtspUrl)
{
    const QString trimmed = rtspUrl.trimmed();
    if (trimmed != _nightRtspUrl) {
        _nightRtspUrl = trimmed;
        emit nightRtspUrlChanged();
        emit pipAvailableChanged();
    }
}

int DhakshaCameraManager::skydroidGimbalStyle() const
{
    return QSettings().value(SkydroidGimbalCamera::kGimbalStyleKey, SkydroidGimbalCamera::kDefaultGimbalStyle).toInt();
}

void DhakshaCameraManager::setSkydroidGimbalStyle(int style)
{
    if (style != skydroidGimbalStyle()) {
        QSettings().setValue(SkydroidGimbalCamera::kGimbalStyleKey, style);
        emit skydroidGimbalStyleChanged();
    }
}

int DhakshaCameraManager::defaultVideoMode(int vendor)
{
    // The ZR10 over the SIYI link froze with QGC's 17 ms buffer; Skydroid plays well with it
    return vendor == VendorZr10 ? VideoSmooth : VideoLowDelay;
}

void DhakshaCameraManager::setVideoMode(int mode)
{
    mode = qBound(static_cast<int>(VideoLowDelay), mode, static_cast<int>(VideoReliable));
    if (mode != _videoMode) {
        _videoMode = mode;
        emit videoModeChanged();
    }
}

int DhakshaCameraManager::gimbalMaxSpeed() const
{
    return qBound(kMinGimbalSpeed, QSettings().value(kGimbalSpeedKey, kMaxGimbalSpeed).toInt(), kMaxGimbalSpeed);
}

void DhakshaCameraManager::setGimbalMaxSpeed(int degreesPerSecond)
{
    degreesPerSecond = qBound(kMinGimbalSpeed, degreesPerSecond, kMaxGimbalSpeed);
    if (degreesPerSecond != gimbalMaxSpeed()) {
        QSettings().setValue(kGimbalSpeedKey, degreesPerSecond);
        emit gimbalMaxSpeedChanged();
    }
}

void DhakshaCameraManager::restoreVendorDefaults()
{
    setCameraIp(defaultCameraIp(_vendor));
    setRtspUrl(defaultRtspUrl(_vendor));
    setNightRtspUrl(defaultNightRtspUrl(_vendor));
}

void DhakshaCameraManager::toggleNightView()
{
    if (_vendor == VendorNone || (!_nightView && _nightRtspUrl.isEmpty())) {
        return;
    }
    _nightView = !_nightView;
    emit nightViewChanged();
    _showVideo(_nightView ? _nightRtspUrl : _rtspUrl);
    _updatePipVideo();
}

bool DhakshaCameraManager::pipAvailable() const
{
    return _vendor != VendorNone && !_nightRtspUrl.isEmpty() && !_rtspUrl.isEmpty();
}

void DhakshaCameraManager::setPipCamera(bool camera)
{
    if (camera != _pipCamera) {
        _pipCamera = camera;
        QSettings settings;
        settings.beginGroup(kSettingsGroup);
        settings.setValue(kPipCameraKey, camera);
        emit pipCameraChanged();
        _updatePipVideo();
    }
}

void DhakshaCameraManager::_updatePipVideo()
{
    // The box shows whichever camera is not on the main screen; with the map in the box the second
    // stream stops, so the radio and the MK15 only carry it while it is shown
    const QString url = _pipCamera && pipAvailable() ? (_nightView ? _rtspUrl : _nightRtspUrl) : QString();
#ifndef DHAKSHA_CAMERA_NO_QGC
    VideoManager* videoManager = qgcApp()->toolbox()->videoManager();
    if (videoManager) {
        videoManager->setSecondaryRtspUrl(url);
    }
#else
    Q_UNUSED(url)
#endif
}

void DhakshaCameraManager::apply()
{
    _saveSettings();
    start();
}

void DhakshaCameraManager::start()
{
    _createCamera();
    _applyVideoMode();
    _startVideoStream();
    _updatePipVideo();
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
    case VendorZr10:
        _camera = new SiyiGimbalCamera(_cameraIp, _rtspUrl, this);
        break;
    default:
        break;
    }
    emit cameraChanged();
}

void DhakshaCameraManager::_startVideoStream()
{
    if (_nightView) {
        _nightView = false;
        emit nightViewChanged();
    }
    if (_vendor != VendorNone) {
        _showVideo(_rtspUrl);
    }
}

void DhakshaCameraManager::_showVideo(const QString& rtspUrl)
{
#ifndef DHAKSHA_CAMERA_NO_QGC
    if (rtspUrl.isEmpty()) {
        return;
    }
    VideoSettings* videoSettings = qgcApp()->toolbox()->settingsManager()->videoSettings();
    videoSettings->rtspUrl()->setRawValue(rtspUrl);
    videoSettings->videoSource()->setRawValue(VideoSettings::videoSourceRTSP);
#else
    Q_UNUSED(rtspUrl)
#endif
}

void DhakshaCameraManager::_applyVideoMode()
{
    const int  latencyMs = _videoMode == VideoSmooth ? 150 : _videoMode == VideoReliable ? 100 : 17;
    const bool tcp       = _videoMode == VideoReliable;

    QSettings settings;
    const bool changed = settings.value(kRtspLatencyKey, 17).toInt() != latencyMs || settings.value(kRtspTcpKey, false).toBool() != tcp;
    settings.setValue(kRtspLatencyKey, latencyMs);
    settings.setValue(kRtspTcpKey, tcp);
#ifndef DHAKSHA_CAMERA_NO_QGC
    // The receiver reads these when it opens the stream, so a running stream is reopened
    VideoManager* videoManager = qgcApp()->toolbox()->videoManager();
    if (changed && videoManager) {
        videoManager->stopVideo();
        videoManager->startVideo();
    }
#else
    Q_UNUSED(changed)
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
