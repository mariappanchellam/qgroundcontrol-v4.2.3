/****************************************************************************
 *
 * (c) 2009-2020 QGROUNDCONTROL PROJECT <http://www.qgroundcontrol.org>
 *
 * QGroundControl is licensed according to the terms in the file
 * COPYING.md in the root of the source code directory.
 *
 ****************************************************************************/

// DhakshaGroundControl motor test page: select one motor (A, B, C...), enter a throttle
// value and press Run, or run all motors in sequence / together. Motors are addressed in
// the autopilot's motor test sequence order so the letters match ArduPilot's motor letters.

import QtQuick          2.3
import QtQuick.Controls 1.2
import QtQuick.Dialogs  1.2

import QGroundControl               1.0
import QGroundControl.Controls      1.0
import QGroundControl.FactSystem    1.0
import QGroundControl.ScreenTools   1.0

SetupPage {
    id:             motorPage
    pageComponent:  pageComponent

    readonly property int _motorTimeoutSecs:    3
    readonly property int _defaultThrottle:     11

    FactPanelController {
        id:             controller
    }

    Component {
        id: pageComponent

        Item {
            id:     pageItem
            width:  availableWidth
            height: Math.max(availableHeight, mainColumn.y + mainColumn.height)

            property var    _vehicle:       controller.vehicle
            property int    _motorCount:    !_vehicle ? 0 : (_vehicle.motorCount == -1 ? 8 : _vehicle.motorCount)
            property int    _selectedMotor: -1      // 0-based index into the test sequence, -1 = none
            property bool   _testEnabled:   safetySwitch.checked
            property bool   _throttleValid: throttleField.acceptableInput
            property int    _throttle:      _throttleValid ? parseInt(throttleField.text) : 0
            property string _status:        ""

            function motorLetter(index) {
                return vehicleComponent ? vehicleComponent.motorIndexToLetter(index) : ""
            }

            function runSelected() {
                _vehicle.motorTestSequence(_selectedMotor + 1, _throttle, _motorTimeoutSecs, 0)
                _setRunning(qsTr("Running Motor %1 at %2% for %3 s").arg(motorLetter(_selectedMotor)).arg(_throttle).arg(_motorTimeoutSecs), _motorTimeoutSecs)
            }

            function runAllSequence() {
                _vehicle.motorTestSequence(1, _throttle, _motorTimeoutSecs, _motorCount)
                _setRunning(qsTr("Running all motors in sequence at %1%, %2 s each").arg(_throttle).arg(_motorTimeoutSecs), _motorTimeoutSecs * _motorCount)
            }

            function runAllTogether() {
                for (var i = 0; i < _motorCount; i++) {
                    _vehicle.motorTestSequence(i + 1, _throttle, _motorTimeoutSecs, 0)
                }
                _setRunning(qsTr("Running all motors at %1% for %2 s").arg(_throttle).arg(_motorTimeoutSecs), _motorTimeoutSecs)
            }

            function stopAll() {
                _vehicle.motorTestSequence(1, 0, 0, 0)
                statusTimer.stop()
                _status = qsTr("Stopped")
            }

            function _setRunning(text, seconds) {
                _status = text
                statusTimer.interval = seconds * 1000
                statusTimer.restart()
            }

            Timer {
                id:             statusTimer
                repeat:         false
                onTriggered:    pageItem._status = ""
            }

            Column {
                id:                         mainColumn
                anchors.horizontalCenter:   parent.horizontalCenter
                y:                          Math.max(0, pageItem.height * 0.12)
                spacing:                    ScreenTools.defaultFontPixelHeight

                QGCLabel {
                    anchors.horizontalCenter:   parent.horizontalCenter
                    text:                       qsTr("Warning: Unable to determine motor count")
                    color:                      qgcPal.warningText
                    visible:                    pageItem._vehicle ? pageItem._vehicle.motorCount == -1 : false
                }

                // 1. Slide switch enabling / disabling all motor controls
                Row {
                    anchors.horizontalCenter:   parent.horizontalCenter
                    spacing:                    ScreenTools.defaultFontPixelWidth

                    Switch {
                        id:                     safetySwitch
                        anchors.verticalCenter: parent.verticalCenter
                        onCheckedChanged: {
                            if (!checked) {
                                pageItem.stopAll()
                                pageItem._selectedMotor = -1
                                pageItem._status = ""
                            }
                        }
                    }

                    QGCLabel {
                        anchors.verticalCenter: parent.verticalCenter
                        color:                  qgcPal.warningText
                        text:                   safetySwitch.checked ? qsTr("Careful: Motor test is enabled") : qsTr("Propellers are removed - Enable motor test")
                    }
                }

                // 2. Motor buttons: selecting one disables the others
                Row {
                    anchors.horizontalCenter:   parent.horizontalCenter
                    spacing:                    ScreenTools.defaultFontPixelWidth

                    Repeater {
                        model: pageItem._motorCount

                        QGCButton {
                            text:       qsTr("Motor %1").arg(pageItem.motorLetter(index))
                            checkable:  false
                            checked:    pageItem._selectedMotor === index
                            enabled:    pageItem._testEnabled && (pageItem._selectedMotor === -1 || pageItem._selectedMotor === index)
                            onClicked:  pageItem._selectedMotor = (pageItem._selectedMotor === index ? -1 : index)
                        }
                    }
                }

                // 3. Throttle value + Run / Stop
                Row {
                    anchors.horizontalCenter:   parent.horizontalCenter
                    spacing:                    ScreenTools.defaultFontPixelWidth

                    QGCLabel {
                        anchors.verticalCenter: parent.verticalCenter
                        text:                   qsTr("Throttle (%)")
                    }

                    QGCTextField {
                        id:                     throttleField
                        anchors.verticalCenter: parent.verticalCenter
                        width:                  ScreenTools.defaultFontPixelWidth * 8
                        text:                   _defaultThrottle
                        enabled:                pageItem._testEnabled
                        inputMethodHints:       Qt.ImhDigitsOnly
                        validator:              IntValidator { bottom: 0; top: 100 }
                    }

                    QGCButton {
                        anchors.verticalCenter: parent.verticalCenter
                        text:                   qsTr("Run")
                        primary:                true
                        enabled:                pageItem._testEnabled && pageItem._selectedMotor !== -1 && pageItem._throttleValid
                        onClicked:              pageItem.runSelected()
                    }

                    QGCButton {
                        anchors.verticalCenter: parent.verticalCenter
                        text:                   qsTr("Stop")
                        enabled:                pageItem._testEnabled
                        onClicked:              pageItem.stopAll()
                    }
                }

                // 4. All motors
                Row {
                    anchors.horizontalCenter:   parent.horizontalCenter
                    spacing:                    ScreenTools.defaultFontPixelWidth

                    QGCButton {
                        text:       qsTr("Run All in Sequence")
                        enabled:    pageItem._testEnabled && pageItem._selectedMotor === -1 && pageItem._throttleValid
                        onClicked:  pageItem.runAllSequence()
                    }

                    QGCButton {
                        text:       qsTr("Run All Together")
                        enabled:    pageItem._testEnabled && pageItem._selectedMotor === -1 && pageItem._throttleValid
                        onClicked:  pageItem.runAllTogether()
                    }
                }

                QGCLabel {
                    anchors.horizontalCenter:   parent.horizontalCenter
                    text:                       pageItem._status
                    visible:                    text !== ""
                    font.bold:                  true
                }

                QGCLabel {
                    width:                      Math.min(pageItem.width, ScreenTools.defaultFontPixelWidth * 70)
                    anchors.horizontalCenter:   parent.horizontalCenter
                    horizontalAlignment:        Text.AlignHCenter
                    wrapMode:                   Text.WordWrap
                    text:                       qsTr("Running a test spins the motors. Make sure you remove all props. Throttle is 0-100%; each run lasts %1 seconds.").arg(_motorTimeoutSecs)
                }
            } // Column
        } // Item
    } // Component
} // SetupPage
