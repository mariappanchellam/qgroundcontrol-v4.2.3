/****************************************************************************
 *
 * DhakshaGroundControl: gimbal camera controls on the Fly View.
 *
 * Works with any DhakshaGimbalCamera (SIYI, ViewPro, Skydroid); the vendor
 * specific protocol lives in the C++ camera classes. Hold the arrow, zoom
 * and focus buttons; releasing them stops the movement. Tap the title to
 * collapse / expand the panel.
 *
 ****************************************************************************/

import QtQuick          2.12
import QtQuick.Controls 2.4
import QtQuick.Layouts  1.12

import QGroundControl.Controls      1.0
import QGroundControl.DhakshaCamera 1.0
import QGroundControl.Palette       1.0
import QGroundControl.ScreenTools   1.0

Rectangle {
    id:         root
    width:      mainLayout.width  + (_margins * 2)
    height:     mainLayout.height + (_margins * 2)
    color:      "#80000000"
    radius:     _margins
    visible:    _camera && _camera.controlSupported

    property var    _camera:        DhakshaCameraManager.camera
    property real   _margins:       ScreenTools.defaultFontPixelHeight / 2
    property real   _buttonSize:    ScreenTools.defaultFontPixelHeight * 2
    property int    _gimbalSpeed:   50      // percent of maximum rotation speed
    property int    _pipMode:       0
    property bool   _expanded:      true

    QGCPalette { id: qgcPal; colorGroupEnabled: true }

    // Button that runs holdAction while pressed and releaseAction when let go
    component HoldButton: QGCButton {
        property var holdAction:    function() {}
        property var releaseAction: function() {}
        implicitWidth:  _buttonSize
        implicitHeight: _buttonSize
        onPressed:      holdAction()
        onReleased:     releaseAction()
        onCanceled:     releaseAction()
    }

    ColumnLayout {
        id:                 mainLayout
        anchors.centerIn:   parent
        spacing:            _margins / 2

        // Title with connection indicator; tap to collapse
        RowLayout {
            Layout.alignment:   Qt.AlignHCenter
            spacing:            _margins / 2

            Rectangle {
                width:  ScreenTools.defaultFontPixelHeight * 0.6
                height: width
                radius: width / 2
                color:  _camera && _camera.connected ? "lime" : "red"
            }
            QGCLabel {
                text:       _camera ? qsTr("%1 Camera %2").arg(_camera.vendorName).arg(_expanded ? "▴" : "▾") : ""
                color:      "white"
                font.bold:  true

                MouseArea {
                    anchors.fill:   parent
                    onClicked:      _expanded = !_expanded
                }
            }
        }

        RowLayout {
            visible:    _expanded
            spacing:    _margins

            // Left: gimbal direction pad + speed
            ColumnLayout {
                spacing: _margins / 2

                GridLayout {
                    Layout.alignment:   Qt.AlignHCenter
                    columns:            3
                    rowSpacing:         _margins / 4
                    columnSpacing:      _margins / 4

                    Item { width: 1; height: 1 }
                    HoldButton {
                        text:           "▲"
                        holdAction:     function() { _camera.gimbalSpeed(0, _gimbalSpeed) }
                        releaseAction:  function() { _camera.gimbalStop() }
                    }
                    Item { width: 1; height: 1 }

                    HoldButton {
                        text:           "◀"
                        holdAction:     function() { _camera.gimbalSpeed(-_gimbalSpeed, 0) }
                        releaseAction:  function() { _camera.gimbalStop() }
                    }
                    QGCButton {
                        implicitWidth:  _buttonSize
                        implicitHeight: _buttonSize
                        text:           "◎"
                        onClicked:      _camera.gimbalCenter()
                    }
                    HoldButton {
                        text:           "▶"
                        holdAction:     function() { _camera.gimbalSpeed(_gimbalSpeed, 0) }
                        releaseAction:  function() { _camera.gimbalStop() }
                    }

                    Item { width: 1; height: 1 }
                    HoldButton {
                        text:           "▼"
                        holdAction:     function() { _camera.gimbalSpeed(0, -_gimbalSpeed) }
                        releaseAction:  function() { _camera.gimbalStop() }
                    }
                    Item { width: 1; height: 1 }
                }

                RowLayout {
                    spacing: _margins / 4
                    QGCLabel { text: qsTr("Speed"); color: "white" }
                    Slider {
                        Layout.preferredWidth:  _buttonSize * 2.2
                        from:                   10
                        to:                     100
                        stepSize:               10
                        value:                  _gimbalSpeed
                        onMoved:                _gimbalSpeed = value
                    }
                }

                RowLayout {
                    Layout.alignment:   Qt.AlignHCenter
                    spacing:            _margins / 4
                    QGCButton { text: qsTr("Lock");   onClicked: _camera.gimbalLock() }
                    QGCButton { text: qsTr("Follow"); onClicked: _camera.gimbalFollow() }
                }
            }

            // Right: lens and capture
            GridLayout {
                columns:        3
                rowSpacing:     _margins / 4
                columnSpacing:  _margins / 4

                QGCLabel { text: qsTr("Zoom"); color: "white" }
                HoldButton {
                    text:           "−"
                    holdAction:     function() { _camera.zoomOut() }
                    releaseAction:  function() { _camera.zoomStop() }
                }
                HoldButton {
                    text:           "+"
                    holdAction:     function() { _camera.zoomIn() }
                    releaseAction:  function() { _camera.zoomStop() }
                }

                QGCLabel { text: qsTr("Focus"); color: "white" }
                HoldButton {
                    text:           "−"
                    holdAction:     function() { _camera.focusOut() }
                    releaseAction:  function() { _camera.focusStop() }
                }
                HoldButton {
                    text:           "+"
                    holdAction:     function() { _camera.focusIn() }
                    releaseAction:  function() { _camera.focusStop() }
                }

                QGCButton {
                    Layout.columnSpan:  3
                    Layout.fillWidth:   true
                    text:               qsTr("Auto Focus")
                    onClicked:          _camera.autoFocus()
                }
                QGCButton {
                    Layout.columnSpan:  3
                    Layout.fillWidth:   true
                    text:               qsTr("Photo")
                    onClicked:          _camera.takePhoto()
                }
                QGCButton {
                    Layout.columnSpan:  3
                    Layout.fillWidth:   true
                    text:               _camera && _camera.recording ? qsTr("Stop Recording") : qsTr("Record")
                    primary:            _camera && _camera.recording
                    onClicked:          _camera.toggleRecording()
                }
                QGCButton {
                    Layout.columnSpan:  3
                    Layout.fillWidth:   true
                    text:               qsTr("Day / Night")
                    onClicked:          _camera.toggleDayNight()
                }
                QGCButton {
                    Layout.columnSpan:  3
                    Layout.fillWidth:   true
                    text:               qsTr("PIP mode %1").arg(_pipMode)
                    onClicked: {
                        _pipMode = (_pipMode + 1) % 4
                        _camera.setPipMode(_pipMode)
                    }
                }
            }
        }
    }
}
