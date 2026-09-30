/****************************************************************************
 *
 * DhakshaGroundControl: vendor-neutral gimbal camera interface.
 *
 * One interface for every supported payload vendor (SIYI, ViewPro, Skydroid).
 * The QML camera controls only talk to this class; each vendor subclass
 * translates the calls into its own control protocol. Only one camera is
 * connected to the drone at a time, so DhakshaCameraManager owns a single
 * instance.
 *
 ****************************************************************************/

#pragma once

#include <QObject>
#include <QString>

class DhakshaGimbalCamera : public QObject
{
    Q_OBJECT

public:
    explicit DhakshaGimbalCamera(const QString& vendorName, const QString& rtspUrl, QObject* parent = nullptr);

    Q_PROPERTY(QString  vendorName          READ vendorName         CONSTANT)
    Q_PROPERTY(QString  rtspUrl             READ rtspUrl            CONSTANT)
    Q_PROPERTY(bool     controlSupported    READ controlSupported   CONSTANT)   ///< false: video stream only
    Q_PROPERTY(bool     connected           READ connected          NOTIFY connectedChanged)
    Q_PROPERTY(bool     recording           READ recording          NOTIFY recordingChanged)

    QString vendorName  () const { return _vendorName; }
    QString rtspUrl     () const { return _rtspUrl; }
    bool    connected   () const { return _connected; }
    bool    recording   () const { return _recording; }

    virtual bool controlSupported() const { return false; }

    // Camera
    Q_INVOKABLE virtual void takePhoto          () {}
    Q_INVOKABLE virtual void startRecording     () {}
    Q_INVOKABLE virtual void stopRecording      () {}
    Q_INVOKABLE void         toggleRecording    () { _recording ? stopRecording() : startRecording(); }
    Q_INVOKABLE virtual void toggleDayNight     () {}
    /// 0 = main only, 1 = main + sub, 2 = sub + main, 3 = sub only
    Q_INVOKABLE virtual void setPipMode         (int /*mode*/) {}

    // Lens
    Q_INVOKABLE virtual void zoomIn             () {}
    Q_INVOKABLE virtual void zoomOut            () {}
    Q_INVOKABLE virtual void zoomStop           () {}
    Q_INVOKABLE virtual void focusIn            () {}
    Q_INVOKABLE virtual void focusOut           () {}
    Q_INVOKABLE virtual void focusStop          () {}
    Q_INVOKABLE virtual void autoFocus          () {}

    // Gimbal
    /// Rotate at the given speeds, each -100..100 (percent of maximum). Positive yaw = right, positive pitch = up.
    Q_INVOKABLE virtual void gimbalSpeed        (int /*yawPercent*/, int /*pitchPercent*/) {}
    Q_INVOKABLE virtual void gimbalStop         () {}
    Q_INVOKABLE virtual void gimbalCenter       () {}
    Q_INVOKABLE virtual void gimbalLock         () {}   ///< Hold earth-frame heading
    Q_INVOKABLE virtual void gimbalFollow       () {}   ///< Follow vehicle heading

signals:
    void connectedChanged   ();
    void recordingChanged   ();

protected:
    void _setConnected(bool connected);
    void _setRecording(bool recording);

private:
    QString _vendorName;
    QString _rtspUrl;
    bool    _connected = false;
    bool    _recording = false;
};
