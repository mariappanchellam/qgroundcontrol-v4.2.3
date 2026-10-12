/****************************************************************************
 *
 * DhakshaGroundControl: gimbal camera controls on the Fly View.
 *
 * Laid out like the camera makers' own apps, for a 5.5 inch screen: round
 * icon buttons split around the video instead of one panel.
 *   left:        record, photo, and a gimbal joystick (drag the knob; the
 *                further from the centre, the faster the gimbal turns)
 *   right:       centre, tilt down 90, lock / follow, zoom in / out, laser range
 *   top centre:  connection, day / night camera, PIP, IR, auto focus, log
 * A small label under the top row shows recording time, the laser range and
 * other confirmations. Works with any DhakshaGimbalCamera; the vendor
 * protocol lives in the C++ camera classes.
 *
 ****************************************************************************/

import QtQuick          2.12
import QtQuick.Controls 2.4
import QtQuick.Layouts  1.12

import QGroundControl.Controls      1.0
import QGroundControl.DhakshaCamera 1.0
import QGroundControl.ScreenTools   1.0

Item {
    id:         root
    visible:    _camera && _camera.controlSupported

    // Space taken by other Fly View widgets, set by the parent layer
    property real leftInset:        0   ///< right edge of the tool strip
    property real bottomLeftInset:  0   ///< right edge of the picture-in-picture window at the bottom left
    property real topInset:         0
    property real rightTopInset:    0   ///< bottom of the instrument panel at the top right

    property var    _camera:        DhakshaCameraManager.camera
    property real   _margin:        ScreenTools.defaultFontPixelHeight / 2
    property real   _buttonSize:    ScreenTools.defaultFontPixelHeight * 2.6
    property real   _smallButton:   ScreenTools.defaultFontPixelHeight * 2.1
    property real   _joystickSize:  _buttonSize * 2.8
    property bool   _locked:        true
    property bool   _showLog:       false
    property int    _pipMode:       0
    property int    _recordSeconds: 0

    readonly property string _iconPath: "/res/DhakshaCamera/"
    readonly property color  _orange:   "#F26522"
    readonly property color  _red:      "#D0B71C1C"

    // ------------------------------------------------------------------ Building blocks

    // Round icon button; holdAction runs while pressed, releaseAction when let go
    component IconButton: Rectangle {
        id:             button
        width:          _buttonSize
        height:         width
        radius:         width / 2
        color:          active ? activeColor : (touch.pressed ? "#C0404040" : "#A0202020")
        border.color:   "#60FFFFFF"
        border.width:   1

        property string icon
        property bool   active:         false
        property color  activeColor:    _orange
        property var    clickAction:    null
        property var    holdAction:     null
        property var    releaseAction:  null

        QGCColoredImage {
            anchors.centerIn:   parent
            width:              parent.width * 0.52
            height:             width
            source:             _iconPath + button.icon + ".svg"
            sourceSize.height:  height
            fillMode:           Image.PreserveAspectFit
            color:              "white"
        }

        MouseArea {
            id:                 touch
            anchors.fill:       parent
            preventStealing:    true
            onPressed:          if (button.holdAction) button.holdAction()
            onReleased:         if (button.releaseAction) button.releaseAction()
            onCanceled:         if (button.releaseAction) button.releaseAction()
            onClicked:          if (button.clickAction) button.clickAction()
        }
    }

    function notify(text) {
        toastText.text = text
        toast.opacity = 1
        toastTimer.restart()
    }

    // ------------------------------------------------------------------ Status label timers

    Timer {
        id:             toastTimer
        interval:       2000
        onTriggered:    toast.opacity = 0
    }

    Timer {
        interval:       1000
        repeat:         true
        running:        _camera && _camera.recording
        onTriggered:    _recordSeconds++
    }

    Connections {
        target: _camera
        function onRecordingChanged() {
            _recordSeconds = 0
            if (!_camera.recording) {
                notify(qsTr("Recording saved"))
            }
        }
    }

    // ------------------------------------------------------------------ Top centre

    Column {
        anchors.top:                parent.top
        anchors.topMargin:          topInset + _margin
        anchors.horizontalCenter:   parent.horizontalCenter
        spacing:                    _margin / 2

        Row {
            anchors.horizontalCenter:   parent.horizontalCenter
            spacing:                    _margin

            // Connection: green when the camera answers
            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                width:                  ScreenTools.defaultFontPixelHeight * 0.7
                height:                 width
                radius:                 width / 2
                color:                  _camera && _camera.connected ? "lime" : "red"
            }
            IconButton {
                width:          _smallButton
                visible:        DhakshaCameraManager.nightRtspUrl !== ""
                icon:           DhakshaCameraManager.nightView ? "day" : "night"
                active:         DhakshaCameraManager.nightView
                clickAction:    function() {
                    DhakshaCameraManager.toggleNightView()
                    notify(DhakshaCameraManager.nightView ? qsTr("Night camera") : qsTr("Day camera"))
                }
            }
            IconButton {
                width:          _smallButton
                icon:           "pip"
                clickAction:    function() {
                    _pipMode = (_pipMode + 1) % 4
                    _camera.setPipMode(_pipMode)
                    notify(qsTr("PIP mode %1").arg(_pipMode))
                }
            }
            IconButton {
                width:          _smallButton
                icon:           "ir"
                clickAction:    function() { _camera.toggleDayNight(); notify(qsTr("Day / night mode")) }
            }
            IconButton {
                width:          _smallButton
                icon:           "focus"
                clickAction:    function() { _camera.autoFocus(); notify(qsTr("Auto focus")) }
            }
            IconButton {
                width:          _smallButton
                icon:           "log"
                active:         _showLog
                clickAction:    function() { _showLog = !_showLog }
            }
        }

        // Recording time and laser range stay; other confirmations fade after 2 s
        Row {
            anchors.horizontalCenter:   parent.horizontalCenter
            spacing:                    _margin

            Rectangle {
                visible:        _camera && _camera.recording
                width:          recordText.width + height
                height:         recordText.height + _margin
                radius:         height / 2
                color:          _red

                QGCLabel {
                    id:                 recordText
                    anchors.centerIn:   parent
                    color:              "white"
                    font.bold:          true
                    text:               "● REC " + Math.floor(_recordSeconds / 60) + ":" + ("0" + (_recordSeconds % 60)).slice(-2)
                }
            }
            Rectangle {
                visible:        _camera && _camera.rangingActive
                width:          rangeText.width + height
                height:         rangeText.height + _margin
                radius:         height / 2
                color:          "#C0202020"

                QGCLabel {
                    id:                 rangeText
                    anchors.centerIn:   parent
                    color:              "white"
                    font.bold:          true
                    text:               qsTr("Range %1").arg(_camera && _camera.distanceText !== "" ? _camera.distanceText : "…")
                }
            }
            Rectangle {
                id:             toast
                opacity:        0
                visible:        opacity > 0
                width:          toastText.width + height
                height:         toastText.height + _margin
                radius:         height / 2
                color:          "#C0202020"

                Behavior on opacity { NumberAnimation { duration: 250 } }

                QGCLabel {
                    id:                 toastText
                    anchors.centerIn:   parent
                    color:              "white"
                }
            }
        }

        // Commands sent (TX) and camera replies (RX), newest first; press and hold to clear
        Rectangle {
            anchors.horizontalCenter:   parent.horizontalCenter
            visible:                    _showLog
            width:                      Math.min(root.width * 0.5, ScreenTools.defaultFontPixelWidth * 60)
            height:                     logColumn.height + _margin
            radius:                     _margin
            color:                      "#C0000000"

            Column {
                id:                 logColumn
                anchors.centerIn:   parent
                width:              parent.width - _margin * 2

                Repeater {
                    model: _camera ? _camera.commandLog.slice(0, 6) : []
                    QGCLabel {
                        width:          logColumn.width
                        text:           modelData
                        color:          "white"
                        elide:          Text.ElideRight
                        font.family:    ScreenTools.fixedFontFamily
                        font.pointSize: ScreenTools.smallFontPointSize
                    }
                }
                QGCLabel {
                    visible:        _camera && _camera.commandLog.length === 0
                    text:           qsTr("No commands sent yet")
                    color:          "white"
                    font.pointSize: ScreenTools.smallFontPointSize
                }
            }
            MouseArea {
                anchors.fill:   parent
                onPressAndHold: _camera.clearCommandLog()
            }
        }
    }

    // ------------------------------------------------------------------ Left: record, photo

    Column {
        anchors.left:           parent.left
        anchors.leftMargin:     leftInset + _margin
        anchors.verticalCenter: parent.verticalCenter
        spacing:                _margin

        IconButton {
            icon:           "record"
            active:         _camera && _camera.recording
            activeColor:    _red
            clickAction:    function() {
                if (!_camera.recording) {
                    notify(qsTr("Recording started"))
                }
                _camera.toggleRecording()
            }
        }
        IconButton {
            icon:           "photo"
            clickAction:    function() { _camera.takePhoto(); notify(qsTr("Photo taken")) }
        }
    }

    // ------------------------------------------------------------------ Bottom left: gimbal joystick

    Item {
        id:                     joystick
        width:                  _joystickSize
        height:                 width
        anchors.left:           parent.left
        anchors.leftMargin:     Math.max(leftInset, bottomLeftInset) + _margin
        anchors.bottom:         parent.bottom
        anchors.bottomMargin:   _margin

        property real   _range:     (width - knob.width) / 2
        property real   _dx:        0
        property real   _dy:        0
        property int    _yawSent:   0
        property int    _pitchSent: 0

        // Dead zone in the middle, then speed grows with the square of the distance for fine aiming
        function speed(fraction) {
            var magnitude = Math.max(0, (Math.abs(fraction) - 0.12) / 0.88)
            return Math.round((fraction < 0 ? -1 : 1) * magnitude * magnitude * 100)
        }

        function sendSpeed() {
            var yaw   = speed(_dx / _range)
            var pitch = speed(-_dy / _range)
            if (yaw !== _yawSent || pitch !== _pitchSent) {
                _yawSent   = yaw
                _pitchSent = pitch
                _camera.gimbalSpeed(yaw, pitch)
            }
        }

        Rectangle {
            anchors.fill:   parent
            radius:         width / 2
            color:          "#40000000"
            border.color:   "white"
            border.width:   2
        }

        Rectangle {
            id:         knob
            width:      joystick.width * 0.3
            height:     width
            radius:     width / 2
            color:      _orange
            x:          (joystick.width - width) / 2 + joystick._dx
            y:          (joystick.height - height) / 2 + joystick._dy

            Behavior on x { enabled: !joystickArea.pressed; NumberAnimation { duration: 120 } }
            Behavior on y { enabled: !joystickArea.pressed; NumberAnimation { duration: 120 } }
        }

        MouseArea {
            id:                 joystickArea
            anchors.fill:       parent
            preventStealing:    true

            function move(mouseX, mouseY) {
                var dx = mouseX - width / 2
                var dy = mouseY - height / 2
                var distance = Math.sqrt(dx * dx + dy * dy)
                if (distance > joystick._range) {
                    dx *= joystick._range / distance
                    dy *= joystick._range / distance
                }
                joystick._dx = dx
                joystick._dy = dy
                joystick.sendSpeed()
            }

            function release() {
                joystick._dx = 0
                joystick._dy = 0
                joystick._yawSent = 0
                joystick._pitchSent = 0
                _camera.gimbalStop()
            }

            onPressed:          move(mouse.x, mouse.y)
            onPositionChanged:  move(mouse.x, mouse.y)
            onReleased:         release()
            onCanceled:         release()
        }
    }

    // ------------------------------------------------------------------ Right: gimbal and lens

    Grid {
        anchors.right:          parent.right
        anchors.rightMargin:    _margin
        anchors.top:            parent.top
        anchors.topMargin:      Math.max(rightTopInset, topInset) + _margin
        columns:                2
        spacing:                _margin

        IconButton {
            icon:           "center"
            clickAction:    function() { _camera.gimbalCenter(); notify(qsTr("Gimbal centre")) }
        }
        IconButton {
            icon:           "zoom_in"
            holdAction:     function() { _camera.zoomIn() }
            releaseAction:  function() { _camera.zoomStop() }
        }
        IconButton {
            icon:           "down"
            clickAction:    function() { _camera.gimbalDown(); notify(qsTr("Tilt down 90°")) }
        }
        IconButton {
            icon:           "zoom_out"
            holdAction:     function() { _camera.zoomOut() }
            releaseAction:  function() { _camera.zoomStop() }
        }
        IconButton {
            icon:           _locked ? "lock" : "follow"
            clickAction:    function() {
                _locked = !_locked
                if (_locked) {
                    _camera.gimbalLock()
                    notify(qsTr("Gimbal lock"))
                } else {
                    _camera.gimbalFollow()
                    notify(qsTr("Gimbal follow"))
                }
            }
        }
        IconButton {
            visible:        _camera && _camera.rangefinderSupported
            icon:           "laser"
            active:         _camera && _camera.rangingActive
            clickAction:    function() {
                _camera.toggleRanging()
                if (!_camera.rangingActive) {
                    notify(qsTr("Laser range off"))
                }
            }
        }
    }
}
