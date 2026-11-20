/****************************************************************************
 *
 * (c) 2009-2020 QGROUNDCONTROL PROJECT <http://www.qgroundcontrol.org>
 *
 * QGroundControl is licensed according to the terms in the file
 * COPYING.md in the root of the source code directory.
 *
 ****************************************************************************/


import QtQuick                  2.3
import QtQuick.Controls         1.2
import QtQuick.Controls.Styles  1.4
import QtQuick.Dialogs          1.2
import QtQuick.Layouts          1.2

import QGroundControl                       1.0
import QGroundControl.FactSystem            1.0
import QGroundControl.FactControls          1.0
import QGroundControl.Controls              1.0
import QGroundControl.ScreenTools           1.0
import QGroundControl.MultiVehicleManager   1.0
import QGroundControl.Palette               1.0
import QGroundControl.Controllers           1.0
import QGroundControl.SettingsManager       1.0
import QGroundControl.DhakshaCamera         1.0

Rectangle {
    id:                 _root
    color:              qgcPal.window
    anchors.fill:       parent
    anchors.margins:    ScreenTools.defaultFontPixelWidth

    property Fact _savePath:                            QGroundControl.settingsManager.appSettings.savePath
    property Fact _appFontPointSize:                    QGroundControl.settingsManager.appSettings.appFontPointSize
    property Fact _userBrandImageIndoor:                QGroundControl.settingsManager.brandImageSettings.userBrandImageIndoor
    property Fact _userBrandImageOutdoor:               QGroundControl.settingsManager.brandImageSettings.userBrandImageOutdoor
    property Fact _virtualJoystick:                     QGroundControl.settingsManager.appSettings.virtualJoystick
    property Fact _virtualJoystickAutoCenterThrottle:   QGroundControl.settingsManager.appSettings.virtualJoystickAutoCenterThrottle

    // Every label column is at least this wide so the fields line up down the whole page
    property real   _labelWidth:                ScreenTools.defaultFontPixelWidth * 32
    property real   _cardRadius:                Math.round(ScreenTools.defaultFontPixelHeight * 0.5)
    property color  _cardBorderColor:           Qt.rgba(qgcPal.text.r, qgcPal.text.g, qgcPal.text.b, 0.25)
    property real   _cardTitlePointSize:        ScreenTools.mediumFontPointSize
    property real   _comboFieldWidth:           ScreenTools.defaultFontPixelWidth * 30
    property real   _valueFieldWidth:           ScreenTools.defaultFontPixelWidth * 10
    property string _mapProvider:               QGroundControl.settingsManager.flightMapSettings.mapProvider.value
    property string _mapType:                   QGroundControl.settingsManager.flightMapSettings.mapType.value
    property Fact   _followTarget:              QGroundControl.settingsManager.appSettings.followTarget
    property real   _panelWidth:                _root.width * _internalWidthRatio
    property real   _margins:                   ScreenTools.defaultFontPixelWidth
    property var    _planViewSettings:          QGroundControl.settingsManager.planViewSettings
    property var    _flyViewSettings:           QGroundControl.settingsManager.flyViewSettings
    property var    _videoSettings:             QGroundControl.settingsManager.videoSettings
    property string _videoSource:               _videoSettings.videoSource.rawValue
    property bool   _isGst:                     QGroundControl.videoManager.isGStreamer
    property bool   _isUDP264:                  _isGst && _videoSource === _videoSettings.udp264VideoSource
    property bool   _isUDP265:                  _isGst && _videoSource === _videoSettings.udp265VideoSource
    property bool   _isRTSP:                    _isGst && _videoSource === _videoSettings.rtspVideoSource
    property bool   _isTCP:                     _isGst && _videoSource === _videoSettings.tcpVideoSource
    property bool   _isMPEGTS:                  _isGst && _videoSource === _videoSettings.mpegtsVideoSource
    property bool   _videoAutoStreamConfig:     QGroundControl.videoManager.autoStreamConfigured
    property bool   _showSaveVideoSettings:     _isGst || _videoAutoStreamConfig
    property bool   _disableAllDataPersistence: QGroundControl.settingsManager.appSettings.disableAllPersistence.rawValue

    property string gpsDisabled: "Disabled"
    property string gpsUdpPort:  "UDP Port"

    readonly property real _internalWidthRatio: 0.8

        QGCFlickable {
            clip:               true
            anchors.fill:       parent
            contentHeight:      outerItem.height
            contentWidth:       outerItem.width

            Item {
                id:     outerItem
                width:  Math.max(_root.width, settingsColumn.implicitWidth)
                height: settingsColumn.height

                ColumnLayout {
                    id:             settingsColumn
                    anchors.left:   parent.left
                    anchors.right:  parent.right

                    Rectangle {
                        Layout.preferredHeight: flyViewCol.y + flyViewCol.height + _margins
                        Layout.preferredWidth:  flyViewCol.width + (_margins * 2)
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        visible:                QGroundControl.settingsManager.flyViewSettings.visible
                        Layout.fillWidth:       true

                        QGCLabel {
                            id:                 flyViewSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("Fly View")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        ColumnLayout {
                            id:                         flyViewCol
                            anchors.margins:            _margins
                            anchors.top:                flyViewSectionLabel.bottom
                            anchors.left:               parent.left
                            spacing:                    _margins

                            FactCheckBox {
                                id:             useCheckList
                                text:           qsTr("Use Preflight Checklist")
                                fact:           _useChecklist
                                visible:        _useChecklist.visible && QGroundControl.corePlugin.options.preFlightChecklistUrl.toString().length

                                property Fact _useChecklist: QGroundControl.settingsManager.appSettings.useChecklist
                            }

                            FactCheckBox {
                                text:           qsTr("Enforce Preflight Checklist")
                                fact:           _enforceChecklist
                                enabled:        QGroundControl.settingsManager.appSettings.useChecklist.value
                                visible:        useCheckList.visible && _enforceChecklist.visible && QGroundControl.corePlugin.options.preFlightChecklistUrl.toString().length

                                property Fact _enforceChecklist: QGroundControl.settingsManager.appSettings.enforceChecklist
                            }

                            FactCheckBox {
                                text:       qsTr("Keep Map Centered On Vehicle")
                                fact:       _keepMapCenteredOnVehicle
                                visible:    _keepMapCenteredOnVehicle.visible

                                property Fact _keepMapCenteredOnVehicle: QGroundControl.settingsManager.flyViewSettings.keepMapCenteredOnVehicle
                            }

                            FactCheckBox {
                                text:       qsTr("Show Telemetry Log Replay Status Bar")
                                fact:       _showLogReplayStatusBar
                                visible:    _showLogReplayStatusBar.visible

                                property Fact _showLogReplayStatusBar: QGroundControl.settingsManager.flyViewSettings.showLogReplayStatusBar
                            }

                            RowLayout {
                                spacing: ScreenTools.defaultFontPixelWidth

                                FactCheckBox {
                                    text:       qsTr("Virtual Joystick")
                                    visible:    _virtualJoystick.visible
                                    fact:       _virtualJoystick
                                }

                                FactCheckBox {
                                    text:       qsTr("Auto-Center Throttle")
                                    visible:    _virtualJoystickAutoCenterThrottle.visible
                                    enabled:    _virtualJoystick.rawValue
                                    fact:       _virtualJoystickAutoCenterThrottle
                                }
                            }

                            FactCheckBox {
                                text:       qsTr("Use Vertical Instrument Panel")
                                visible:    _alternateInstrumentPanel.visible
                                fact:       _alternateInstrumentPanel

                                property Fact _alternateInstrumentPanel: QGroundControl.settingsManager.flyViewSettings.alternateInstrumentPanel
                            }

                            FactCheckBox {
                                text:       qsTr("Show additional heading indicators on Compass")
                                visible:    _showAdditionalIndicatorsCompass.visible
                                fact:       _showAdditionalIndicatorsCompass

                                property Fact _showAdditionalIndicatorsCompass: QGroundControl.settingsManager.flyViewSettings.showAdditionalIndicatorsCompass
                            }

                            FactCheckBox {
                                text:       qsTr("Lock Compass Nose-Up")
                                visible:    _lockNoseUpCompass.visible
                                fact:       _lockNoseUpCompass

                                property Fact _lockNoseUpCompass: QGroundControl.settingsManager.flyViewSettings.lockNoseUpCompass
                            }

                            FactCheckBox {
                                text:       qsTr("Show simple camera controls (DIGICAM_CONTROL)")
                                visible:    false   // DhakshaGroundControl: the camera panel it controls is hidden
                                fact:       _showDumbCameraControl

                                property Fact _showDumbCameraControl: QGroundControl.settingsManager.flyViewSettings.showSimpleCameraControl
                            }

                            GridLayout {
                                columns:        2
                                columnSpacing:  _margins

                                QGCLabel {
                                    text:               qsTr("Guided Command Settings")
                                    Layout.columnSpan:  2
                                    Layout.alignment:   Qt.AlignLeft
                                }

                                QGCLabel {
                                    text:                   qsTr("Minimum Altitude")
                                    visible:                guidedMinAltField.visible
                                    Layout.minimumWidth:    _labelWidth
                                }
                                FactTextField {
                                    id:                     guidedMinAltField
                                    Layout.preferredWidth:  _valueFieldWidth
                                    visible:                fact.visible
                                    fact:                   _flyViewSettings.guidedMinimumAltitude
                                }

                                QGCLabel {
                                    text:                   qsTr("Maximum Altitude")
                                    visible:                guidedMaxAltField.visible
                                    Layout.minimumWidth:    _labelWidth
                                }
                                FactTextField {
                                    id:                     guidedMaxAltField
                                    Layout.preferredWidth:  _valueFieldWidth
                                    visible:                fact.visible
                                    fact:                   _flyViewSettings.guidedMaximumAltitude
                                }

                                QGCLabel {
                                    text:                   qsTr("Go To Location Max Distance")
                                    visible:                maxGotoDistanceField.visible
                                    Layout.minimumWidth:    _labelWidth
                                }
                                FactTextField {
                                    id:                     maxGotoDistanceField
                                    Layout.preferredWidth:  _valueFieldWidth
                                    visible:                fact.visible
                                    fact:                  _flyViewSettings.maxGoToLocationDistance
                                }
                            }

                            GridLayout {
                                id:             videoGrid
                                columns:        2
                                columnSpacing:  _margins
                                visible:        _videoSettings.visible

                                QGCLabel {
                                    text:               qsTr("Video Settings")
                                    Layout.columnSpan:  2
                                    Layout.alignment:   Qt.AlignLeft
                                }

                                QGCLabel {
                                    id:         videoSourceLabel
                                    text:       qsTr("Source")
                                    visible:    !_videoAutoStreamConfig && _videoSettings.videoSource.visible
                                }
                                FactComboBox {
                                    id:                     videoSource
                                    Layout.preferredWidth:  _comboFieldWidth
                                    indexModel:             false
                                    fact:                   _videoSettings.videoSource
                                    visible:                videoSourceLabel.visible
                                }

                                QGCLabel {
                                    id:         udpPortLabel
                                    text:       qsTr("UDP Port")
                                    visible:    !_videoAutoStreamConfig && (_isUDP264 || _isUDP265 || _isMPEGTS) && _videoSettings.udpPort.visible
                                }
                                FactTextField {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   _videoSettings.udpPort
                                    visible:                udpPortLabel.visible
                                }

                                QGCLabel {
                                    id:         rtspUrlLabel
                                    text:       qsTr("RTSP URL")
                                    visible:    !_videoAutoStreamConfig && _isRTSP && _videoSettings.rtspUrl.visible
                                }
                                FactTextField {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   _videoSettings.rtspUrl
                                    visible:                rtspUrlLabel.visible
                                }

                                QGCLabel {
                                    id:         tcpUrlLabel
                                    text:       qsTr("TCP URL")
                                    visible:    !_videoAutoStreamConfig && _isTCP && _videoSettings.tcpUrl.visible
                                }
                                FactTextField {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   _videoSettings.tcpUrl
                                    visible:                tcpUrlLabel.visible
                                }

                                QGCLabel {
                                    text:                   qsTr("Aspect Ratio")
                                    visible:                !_videoAutoStreamConfig && _isGst && _videoSettings.aspectRatio.visible
                                }
                                FactTextField {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   _videoSettings.aspectRatio
                                    visible:                !_videoAutoStreamConfig && _isGst && _videoSettings.aspectRatio.visible
                                }

                                QGCLabel {
                                    id:         videoFileFormatLabel
                                    text:       qsTr("File Format")
                                    visible:    _showSaveVideoSettings && _videoSettings.recordingFormat.visible
                                }
                                FactComboBox {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   _videoSettings.recordingFormat
                                    visible:                videoFileFormatLabel.visible
                                }

                                QGCLabel {
                                    id:         maxSavedVideoStorageLabel
                                    text:       qsTr("Max Storage Usage")
                                    visible:    _showSaveVideoSettings && _videoSettings.maxVideoSize.visible && _videoSettings.enableStorageLimit.value
                                }
                                FactTextField {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   _videoSettings.maxVideoSize
                                    visible:                _showSaveVideoSettings && _videoSettings.enableStorageLimit.value && maxSavedVideoStorageLabel.visible
                                }

                                QGCLabel {
                                    id:         videoDecodeLabel
                                    text:       qsTr("Video decode priority")
                                    visible:    forceVideoDecoderComboBox.visible
                                }
                                FactComboBox {
                                    id:                     forceVideoDecoderComboBox
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   _videoSettings.forceVideoDecoder
                                    visible:                fact.visible
                                    indexModel:             false
                                }

                                Item { width: _labelWidth; height: 1 }
                                FactCheckBox {
                                    text:       qsTr("Disable When Disarmed")
                                    fact:       _videoSettings.disableWhenDisarmed
                                    visible:    !_videoAutoStreamConfig && _isGst && fact.visible
                                }

                                Item { width: _labelWidth; height: 1 }
                                FactCheckBox {
                                    text:       qsTr("Low Latency Mode")
                                    fact:       _videoSettings.lowLatencyMode
                                    visible:    !_videoAutoStreamConfig && _isGst && fact.visible
                                }

                                Item { width: _labelWidth; height: 1 }
                                FactCheckBox {
                                    text:       qsTr("Auto-Delete Saved Recordings")
                                    fact:       _videoSettings.enableStorageLimit
                                    visible:    _showSaveVideoSettings && fact.visible
                                }
                            }
                        }
                    }

                    // DhakshaGroundControl: gimbal camera vendor / stream selection
                    Item { width: 1; height: _margins }
                    Rectangle {
                        Layout.preferredHeight: gimbalCameraGrid.y + gimbalCameraGrid.height + _margins
                        Layout.preferredWidth:  gimbalCameraGrid.width + (_margins * 2)
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        Layout.fillWidth:       true

                        QGCLabel {
                            id:                 gimbalCameraSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("Gimbal Camera")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        GridLayout {
                            id:                         gimbalCameraGrid
                            anchors.margins:            _margins
                            anchors.top:                gimbalCameraSectionLabel.bottom
                            anchors.left:               parent.left
                            columns:                    2
                            rowSpacing:                 _margins
                            columnSpacing:              _margins

                            QGCLabel { text: qsTr("Camera Vendor"); Layout.minimumWidth: _labelWidth }
                            QGCComboBox {
                                Layout.preferredWidth:  _comboFieldWidth
                                model:                  DhakshaCameraManager.vendorNames
                                currentIndex:           DhakshaCameraManager.vendor
                                onActivated:            DhakshaCameraManager.vendor = index
                            }

                            QGCLabel { text: qsTr("Camera IP") }
                            QGCTextField {
                                Layout.preferredWidth:  _comboFieldWidth
                                text:                   DhakshaCameraManager.cameraIp
                                enabled:                DhakshaCameraManager.vendor !== 0
                                onEditingFinished:      DhakshaCameraManager.cameraIp = text
                            }

                            QGCLabel { text: qsTr("RTSP URL") }
                            QGCTextField {
                                Layout.preferredWidth:  _comboFieldWidth * 1.6
                                text:                   DhakshaCameraManager.rtspUrl
                                enabled:                DhakshaCameraManager.vendor !== 0
                                onEditingFinished:      DhakshaCameraManager.rtspUrl = text
                            }

                            QGCLabel { text: qsTr("Stream to Server") }
                            QGCCheckBox {
                                text:       qsTr("Send camera video to a remote RTMP server")
                                checked:    DhakshaCameraManager.streamForwardEnabled
                                onClicked:  DhakshaCameraManager.streamForwardEnabled = checked
                            }

                            QGCLabel { text: qsTr("Server URL") }
                            QGCTextField {
                                Layout.preferredWidth:  _comboFieldWidth * 1.6
                                text:                   DhakshaCameraManager.streamForwardUrl
                                placeholderText:        "rtmp://server:1935/live/drone1"
                                enabled:                DhakshaCameraManager.streamForwardEnabled
                                onEditingFinished:      DhakshaCameraManager.streamForwardUrl = text
                            }

                            QGCLabel { text: qsTr("Server Status") }
                            QGCLabel {
                                text: !DhakshaCameraManager.streamForwardEnabled ? qsTr("Off")
                                    : QGroundControl.videoManager.streamForwardStatus !== "" ? QGroundControl.videoManager.streamForwardStatus
                                    : qsTr("Press Apply to start")
                                color: QGroundControl.videoManager.streamForwardActive ? "lime" : qgcPal.text
                            }

                            QGCLabel {
                                text:   qsTr("Status")
                            }
                            QGCLabel {
                                property var _camera: DhakshaCameraManager.camera
                                text: !_camera ? qsTr("No camera selected")
                                    : !_camera.controlSupported ? qsTr("%1: video stream only").arg(_camera.vendorName)
                                    : _camera.connected ? qsTr("%1: connected").arg(_camera.vendorName)
                                    : qsTr("%1: no reply from camera").arg(_camera.vendorName)
                            }

                            QGCButton {
                                text:       qsTr("Restore Defaults")
                                enabled:    DhakshaCameraManager.vendor !== 0
                                onClicked:  DhakshaCameraManager.restoreVendorDefaults()
                            }
                            QGCButton {
                                text:       qsTr("Apply and Start Video")
                                primary:    true
                                onClicked:  DhakshaCameraManager.apply()
                            }
                        }
                    }

                    Item { width: 1; height: _margins; visible: planViewCard.visible }
                    Rectangle {
                        id:                     planViewCard
                        Layout.preferredHeight: planViewCol.y + planViewCol.height + _margins
                        Layout.preferredWidth:  planViewCol.width + (_margins * 2)
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        visible:                _planViewSettings.visible
                        Layout.fillWidth:       true

                        QGCLabel {
                            id:                 planViewSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("Plan View")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        ColumnLayout {
                            id:                         planViewCol
                            anchors.margins:            _margins
                            anchors.top:                planViewSectionLabel.bottom
                            anchors.left:               parent.left
                            spacing:                    _margins

                            GridLayout {
                                columns:            2
                                columnSpacing:      _margins
                                visible:            QGroundControl.settingsManager.appSettings.defaultMissionItemAltitude.visible

                                QGCLabel { text: qsTr("Default Mission Altitude"); Layout.minimumWidth: _labelWidth }
                                FactTextField {
                                    Layout.preferredWidth:  _valueFieldWidth
                                    fact:                   QGroundControl.settingsManager.appSettings.defaultMissionItemAltitude
                                }

                                QGCLabel { text: qsTr("VTOL TransitionDistance") }
                                FactTextField {
                                    Layout.preferredWidth:  _valueFieldWidth
                                    fact:                   QGroundControl.settingsManager.planViewSettings.vtolTransitionDistance
                                }
                            }

                            FactCheckBox {
                                text:   qsTr("Use MAV_CMD_CONDITION_GATE for pattern generation")
                                fact:   QGroundControl.settingsManager.planViewSettings.useConditionGate
                            }

                            FactCheckBox {
                                text:       qsTr("Missions Do Not Require Takeoff Item")
                                fact:       _planViewSettings.takeoffItemNotRequired
                                visible:    _planViewSettings.takeoffItemNotRequired.visible
                            }
                        }
                    }

                    Item { width: 1; height: _margins; visible: unitsCard.visible }
                    Rectangle {
                        id:                     unitsCard
                        Layout.preferredHeight: unitsGrid.y + unitsGrid.height + _margins
                        Layout.preferredWidth:  unitsGrid.width + (_margins * 2)
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        visible:                QGroundControl.settingsManager.unitsSettings.visible
                        Layout.fillWidth:       true

                        QGCLabel {
                            id:                 unitsSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("Units")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        GridLayout {
                            id:                         unitsGrid
                            anchors.margins:            _margins
                            anchors.top:                unitsSectionLabel.bottom
                            Layout.fillWidth:           false
                            anchors.left:               parent.left
                            flow:                       GridLayout.TopToBottom
                            rows:                       5
                            columnSpacing:              _margins

                            Repeater {
                                model: [ qsTr("Horizontal Distance"), qsTr("Vertical Distance"), qsTr("Area"), qsTr("Speed"), qsTr("Temperature") ]
                                QGCLabel { text: modelData; Layout.minimumWidth: _labelWidth }
                            }
                            Repeater {
                                model:  [ QGroundControl.settingsManager.unitsSettings.horizontalDistanceUnits, QGroundControl.settingsManager.unitsSettings.verticalDistanceUnits, QGroundControl.settingsManager.unitsSettings.areaUnits, QGroundControl.settingsManager.unitsSettings.speedUnits, QGroundControl.settingsManager.unitsSettings.temperatureUnits ]
                                FactComboBox {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   modelData
                                    indexModel:             false
                                }
                            }
                        }
                    }

                    Item { width: 1; height: _margins; visible: miscCard.visible }
                    Rectangle {
                        id:                     miscCard
                        Layout.preferredWidth:  Math.max(comboGrid.width, miscCol.width) + (_margins * 2)
                        Layout.preferredHeight: (pathRow.visible ? pathRow.y + pathRow.height : miscColItem.y + miscColItem.height)  + (_margins * 2)
                        Layout.fillWidth:       true
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        visible:                QGroundControl.settingsManager.appSettings.visible

                        QGCLabel {
                            id:                 miscSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("Miscellaneous")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        Item {
                            id:                 comboGridItem
                            anchors.margins:    _margins
                            anchors.top:        miscSectionLabel.bottom
                            anchors.left:       parent.left
                            anchors.right:      parent.right
                            height:             comboGrid.height

                            GridLayout {
                                id:                         comboGrid
                                anchors.left:               parent.left
                                columns:                    2
                                columnSpacing:              _margins

                                QGCLabel {
                                    text:           qsTr("Language")
                                    visible: QGroundControl.settingsManager.appSettings.qLocaleLanguage.visible
                                }
                                FactComboBox {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   QGroundControl.settingsManager.appSettings.qLocaleLanguage
                                    indexModel:             false
                                    visible:                QGroundControl.settingsManager.appSettings.qLocaleLanguage.visible
                                }

                                QGCLabel {
                                    text:           qsTr("Color Scheme")
                                    visible: QGroundControl.settingsManager.appSettings.indoorPalette.visible
                                }
                                FactComboBox {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   QGroundControl.settingsManager.appSettings.indoorPalette
                                    indexModel:             false
                                    visible:                QGroundControl.settingsManager.appSettings.indoorPalette.visible
                                }

                                QGCLabel {
                                    text:                   qsTr("Map Provider")
                                    Layout.minimumWidth:    _labelWidth
                                }

                                QGCComboBox {
                                    id:             mapCombo
                                    model:          QGroundControl.mapEngineManager.mapProviderList
                                    Layout.preferredWidth:  _comboFieldWidth
                                    onActivated: {
                                        _mapProvider = textAt(index)
                                        QGroundControl.settingsManager.flightMapSettings.mapProvider.value=textAt(index)
                                        QGroundControl.settingsManager.flightMapSettings.mapType.value=QGroundControl.mapEngineManager.mapTypeList(textAt(index))[0]
                                    }
                                    Component.onCompleted: {
                                        var index = mapCombo.find(_mapProvider)
                                        if(index < 0) index = 0
                                        mapCombo.currentIndex = index
                                    }
                                }
                                QGCLabel {
                                    text:                   qsTr("Map Type")
                                    Layout.minimumWidth:    _labelWidth
                                }
                                QGCComboBox {
                                    id:             mapTypeCombo
                                    model:          QGroundControl.mapEngineManager.mapTypeList(_mapProvider)
                                    Layout.preferredWidth:  _comboFieldWidth
                                    onActivated: {
                                        _mapType = textAt(index)
                                        QGroundControl.settingsManager.flightMapSettings.mapType.value=textAt(index)
                                    }
                                    Component.onCompleted: {
                                        var index = mapTypeCombo.find(_mapType)
                                        if(index < 0) index = 0
                                        mapTypeCombo.currentIndex = index
                                    }
                                }

                                QGCLabel {
                                    text:                   qsTr("Stream GCS Position")
                                    visible:                _followTarget.visible
                                }
                                FactComboBox {
                                    Layout.preferredWidth:  _comboFieldWidth
                                    fact:                   _followTarget
                                    indexModel:             false
                                    visible:                _followTarget.visible
                                }
                                QGCLabel {
                                    text:                           qsTr("UI Scaling")
                                    visible:                        _appFontPointSize.visible
                                    Layout.alignment:               Qt.AlignVCenter
                                }
                                Item {
                                    width:                          _comboFieldWidth
                                    height:                         baseFontEdit.height * 1.5
                                    visible:                        _appFontPointSize.visible
                                    Layout.alignment:               Qt.AlignVCenter
                                    Row {
                                        spacing:                    ScreenTools.defaultFontPixelWidth
                                        anchors.verticalCenter:     parent.verticalCenter
                                        QGCButton {
                                            width:                  height
                                            height:                 baseFontEdit.height * 1.5
                                            text:                   "-"
                                            anchors.verticalCenter: parent.verticalCenter
                                            onClicked: {
                                                if (_appFontPointSize.value > _appFontPointSize.min) {
                                                    _appFontPointSize.value = _appFontPointSize.value - 1
                                                }
                                            }
                                        }
                                        QGCLabel {
                                            id:                     baseFontEdit
                                            width:                  ScreenTools.defaultFontPixelWidth * 6
                                            text:                   (QGroundControl.settingsManager.appSettings.appFontPointSize.value / ScreenTools.platformFontPointSize * 100).toFixed(0) + "%"
                                            horizontalAlignment:    Text.AlignHCenter
                                            anchors.verticalCenter: parent.verticalCenter
                                        }
                                        Text {

                                        }

                                        QGCButton {
                                            width:                  height
                                            height:                 baseFontEdit.height * 1.5
                                            text:                   "+"
                                            anchors.verticalCenter: parent.verticalCenter
                                            onClicked: {
                                                if (_appFontPointSize.value < _appFontPointSize.max) {
                                                    _appFontPointSize.value = _appFontPointSize.value + 1
                                                }
                                            }
                                        }
                                    }
                                }

                                QGCLabel {
                                    text:       qsTr("Auto Fit")
                                    visible:    !ScreenTools.isMobile
                                }
                                FactCheckBox {
                                    text:       qsTr("Scale the interface to fit the window")
                                    fact:       QGroundControl.settingsManager.appSettings.autoFitUI
                                    visible:    !ScreenTools.isMobile
                                }
                            }
                        }

                        Item {
                            id:                 miscColItem
                            anchors.margins:    _margins
                            anchors.left:       parent.left
                            anchors.right:      parent.right
                            anchors.top:        comboGridItem.bottom
                            anchors.topMargin:  ScreenTools.defaultFontPixelHeight
                            height:             miscCol.height

                            ColumnLayout {
                                id:                         miscCol
                                anchors.left:               parent.left
                                spacing:                    _margins

                                FactCheckBox {
                                    text:       qsTr("Use Vehicle Pairing")
                                    fact:       _usePairing
                                    visible:    _usePairing.visible && QGroundControl.supportsPairing
                                    property Fact _usePairing: QGroundControl.settingsManager.appSettings.usePairing
                                }

                                FactCheckBox {
                                    text:       qsTr("Mute all audio output")
                                    fact:       _audioMuted
                                    visible:    _audioMuted.visible
                                    property Fact _audioMuted: QGroundControl.settingsManager.appSettings.audioMuted
                                }

                                FactCheckBox {
                                    text:       qsTr("Check for Internet connection")
                                    fact:       _checkInternet
                                    visible:    _checkInternet && _checkInternet.visible
                                    property Fact _checkInternet: QGroundControl.settingsManager.appSettings.checkInternet
                                }

                                QGCCheckBox {
                                    id:         clearCheck
                                    text:       qsTr("Clear all settings on next start")
                                    checked:    false
                                    onClicked: {
                                        checked ? clearDialog.visible = true : QGroundControl.clearDeleteAllSettingsNextBoot()
                                    }
                                    MessageDialog {
                                        id:                 clearDialog
                                        visible:            false
                                        icon:               StandardIcon.Warning
                                        standardButtons:    StandardButton.Yes | StandardButton.No
                                        title:              qsTr("Clear Settings")
                                        text:               qsTr("All saved settings will be reset the next time you start %1. Is this really what you want?").arg(QGroundControl.appName)
                                        onYes: {
                                            QGroundControl.deleteAllSettingsNextBoot()
                                            clearDialog.visible = false
                                        }
                                        onNo: {
                                            clearCheck.checked  = false
                                            clearDialog.visible = false
                                        }
                                    }
                                }
                            }
                        }

                        //-----------------------------------------------------------------
                        //-- Save path
                        RowLayout {
                            id:                 pathRow
                            anchors.margins:    _margins
                            anchors.left:       parent.left
                            anchors.right:      parent.right
                            anchors.top:        miscColItem.bottom
                            visible:            _savePath.visible && !ScreenTools.isMobile
                            spacing:            _margins

                            QGCLabel { text: qsTr("Application Load/Save Path"); Layout.minimumWidth: _labelWidth }
                            QGCTextField {
                                Layout.fillWidth:   true
                                readOnly:           true
                                text:               _savePath.rawValue === "" ? qsTr("<not set>") : _savePath.value
                            }
                            QGCButton {
                                text:       qsTr("Browse")
                                onClicked:  savePathBrowseDialog.openForLoad()
                                QGCFileDialog {
                                    id:             savePathBrowseDialog
                                    title:          qsTr("Choose the location to save/load files")
                                    folder:         _savePath.rawValue
                                    selectExisting: true
                                    selectFolder:   true
                                    onAcceptedForLoad: _savePath.rawValue = file
                                }
                            }
                        }
                    }

                    Item { width: 1; height: _margins; visible: telemetryRect.visible }
                    Rectangle {
                        id:                     telemetryRect
                        Layout.preferredHeight: loggingCol.y + loggingCol.height + _margins
                        Layout.preferredWidth:  loggingCol.width + (_margins * 2)
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        Layout.fillWidth:       true
                        visible:                promptSaveLog._telemetrySave.visible || logIfNotArmed._telemetrySaveNotArmed.visible || promptSaveCsv._saveCsvTelemetry.visible

                        QGCLabel {
                            id:                 telemetryLogSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("Telemetry Logs from Vehicle")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        ColumnLayout {
                            id:                         loggingCol
                            anchors.margins:            _margins
                            anchors.top:                telemetryLogSectionLabel.bottom
                            anchors.left:               parent.left
                            spacing:                    _margins
                            FactCheckBox {
                                id:         promptSaveLog
                                text:       qsTr("Save log after each flight")
                                fact:       _telemetrySave
                                visible:    _telemetrySave.visible
                                enabled:    !_disableAllDataPersistence
                                property Fact _telemetrySave: QGroundControl.settingsManager.appSettings.telemetrySave
                            }
                            FactCheckBox {
                                id:         logIfNotArmed
                                text:       qsTr("Save logs even if vehicle was not armed")
                                fact:       _telemetrySaveNotArmed
                                visible:    _telemetrySaveNotArmed.visible
                                enabled:    promptSaveLog.checked && !_disableAllDataPersistence
                                property Fact _telemetrySaveNotArmed: QGroundControl.settingsManager.appSettings.telemetrySaveNotArmed
                            }
                            FactCheckBox {
                                id:         promptSaveCsv
                                text:       qsTr("Save CSV log of telemetry data")
                                fact:       _saveCsvTelemetry
                                visible:    _saveCsvTelemetry.visible
                                enabled:    !_disableAllDataPersistence
                                property Fact _saveCsvTelemetry: QGroundControl.settingsManager.appSettings.saveCsvTelemetry
                            }
                        }
                    }

                    Item { width: 1; height: _margins; visible: autoConnectCard.visible }
                    Rectangle {
                        id:                     autoConnectCard
                        Layout.preferredWidth:  autoConnectCol.width + (_margins * 2)
                        Layout.preferredHeight: autoConnectCol.y + autoConnectCol.height + _margins
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        visible:                QGroundControl.settingsManager.autoConnectSettings.visible
                        Layout.fillWidth:       true

                        QGCLabel {
                            id:                 autoConnectSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("AutoConnect to the following devices")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        ColumnLayout {
                            id:                 autoConnectCol
                            anchors.margins:    _margins
                            anchors.left:       parent.left
                            anchors.top:        autoConnectSectionLabel.bottom
                            spacing:            _margins

                            // Listed vertically so the page never grows wider than the window
                            ColumnLayout {
                                spacing: _margins

                                Repeater {
                                    id:     autoConnectRepeater
                                    model:  [ QGroundControl.settingsManager.autoConnectSettings.autoConnectPixhawk,
                                        QGroundControl.settingsManager.autoConnectSettings.autoConnectSiKRadio,
                                        QGroundControl.settingsManager.autoConnectSettings.autoConnectPX4Flow,
                                        QGroundControl.settingsManager.autoConnectSettings.autoConnectLibrePilot,
                                        QGroundControl.settingsManager.autoConnectSettings.autoConnectUDP,
                                        QGroundControl.settingsManager.autoConnectSettings.autoConnectRTKGPS,
                                        QGroundControl.settingsManager.autoConnectSettings.autoConnectZeroConf,
                                    ]

                                    property var names: [ qsTr("Pixhawk"), qsTr("SiK Radio"), qsTr("PX4 Flow"), qsTr("LibrePilot"), qsTr("UDP"), qsTr("RTK GPS"), qsTr("Zero-Conf") ]

                                    FactCheckBox {
                                        text:       autoConnectRepeater.names[index]
                                        fact:       modelData
                                        visible:    modelData.visible
                                    }
                                }
                            }

                            GridLayout {
                                Layout.fillWidth:   false
                                Layout.alignment:   Qt.AlignLeft
                                columns:            2
                                columnSpacing:      _margins
                                visible:            !ScreenTools.isMobile
                                                    && QGroundControl.settingsManager.autoConnectSettings.autoConnectNmeaPort.visible
                                                    && QGroundControl.settingsManager.autoConnectSettings.autoConnectNmeaBaud.visible

                                QGCLabel {
                                    text:                   qsTr("NMEA GPS Device")
                                    Layout.minimumWidth:    _labelWidth
                                }
                                QGCComboBox {
                                    id:                     nmeaPortCombo
                                    Layout.preferredWidth:  _comboFieldWidth

                                    model:  ListModel {
                                    }

                                    onActivated: {
                                        if (index != -1) {
                                            QGroundControl.settingsManager.autoConnectSettings.autoConnectNmeaPort.value = textAt(index);
                                        }
                                    }
                                    Component.onCompleted: {
                                        model.append({text: gpsDisabled})
                                        model.append({text: gpsUdpPort})

                                        for (var i in QGroundControl.linkManager.serialPorts) {
                                            nmeaPortCombo.model.append({text:QGroundControl.linkManager.serialPorts[i]})
                                        }
                                        var index = nmeaPortCombo.find(QGroundControl.settingsManager.autoConnectSettings.autoConnectNmeaPort.valueString);
                                        nmeaPortCombo.currentIndex = index;
                                        if (QGroundControl.linkManager.serialPorts.length === 0) {
                                            nmeaPortCombo.model.append({text: "Serial <none available>"})
                                        }
                                    }
                                }

                                QGCLabel {
                                    visible:          nmeaPortCombo.currentText !== gpsUdpPort && nmeaPortCombo.currentText !== gpsDisabled
                                    text:             qsTr("NMEA GPS Baudrate")
                                }
                                QGCComboBox {
                                    visible:                nmeaPortCombo.currentText !== gpsUdpPort && nmeaPortCombo.currentText !== gpsDisabled
                                    id:                     nmeaBaudCombo
                                    Layout.preferredWidth:  _comboFieldWidth
                                    model:                  [4800, 9600, 19200, 38400, 57600, 115200]

                                    onActivated: {
                                        if (index != -1) {
                                            QGroundControl.settingsManager.autoConnectSettings.autoConnectNmeaBaud.value = textAt(index);
                                        }
                                    }
                                    Component.onCompleted: {
                                        var index = nmeaBaudCombo.find(QGroundControl.settingsManager.autoConnectSettings.autoConnectNmeaBaud.valueString);
                                        nmeaBaudCombo.currentIndex = index;
                                    }
                                }

                                QGCLabel {
                                    text:       qsTr("NMEA stream UDP port")
                                    visible:    nmeaPortCombo.currentText === gpsUdpPort
                                }
                                FactTextField {
                                    visible:                nmeaPortCombo.currentText === gpsUdpPort
                                    Layout.preferredWidth:  _valueFieldWidth
                                    fact:                   QGroundControl.settingsManager.autoConnectSettings.nmeaUdpPort
                                }
                            }
                        }
                    }

                    Item { width: 1; height: _margins; visible: rtkCard.visible }
                    Rectangle {
                        id:                     rtkCard
                        Layout.preferredHeight: rtkGrid.y + rtkGrid.height + _margins
                        Layout.preferredWidth:  rtkGrid.width + (_margins * 2)
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        visible:                QGroundControl.settingsManager.rtkSettings.visible
                        Layout.fillWidth:       true

                        QGCLabel {
                            id:                 rtkSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("RTK GPS")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        GridLayout {
                            id:                         rtkGrid
                            anchors.margins:            _margins
                            anchors.top:                rtkSectionLabel.bottom
                            Layout.fillWidth:           true
                            anchors.left:               parent.left
                            columns:                    3
                            columnSpacing:              _margins

                            property var  rtkSettings:      QGroundControl.settingsManager.rtkSettings
                            property bool useFixedPosition: rtkSettings.useFixedBasePosition.rawValue
                            property real firstColWidth:    ScreenTools.defaultFontPixelWidth * 3

                            QGCRadioButton {
                                text:               qsTr("Perform Survey-In")
                                visible:            rtkGrid.rtkSettings.useFixedBasePosition.visible
                                checked:            rtkGrid.rtkSettings.useFixedBasePosition.value === false
                                Layout.columnSpan:  3
                                onClicked:          rtkGrid.rtkSettings.useFixedBasePosition.value = false
                            }

                            Item { width: rtkGrid.firstColWidth; height: 1 }
                            QGCLabel {
                                text:               rtkGrid.rtkSettings.surveyInAccuracyLimit.shortDescription
                                visible:            rtkGrid.rtkSettings.surveyInAccuracyLimit.visible
                                enabled:            !rtkGrid.useFixedPosition
                                // The indent column sits in front of this one, so take it off the shared label width
                                Layout.minimumWidth: _labelWidth - rtkGrid.firstColWidth - _margins
                            }
                            FactTextField {
                                fact:               rtkGrid.rtkSettings.surveyInAccuracyLimit
                                visible:            rtkGrid.rtkSettings.surveyInAccuracyLimit.visible
                                enabled:            !rtkGrid.useFixedPosition
                                Layout.preferredWidth:  _valueFieldWidth
                            }

                            Item { width: rtkGrid.firstColWidth; height: 1 }
                            QGCLabel {
                                text:               rtkGrid.rtkSettings.surveyInMinObservationDuration.shortDescription
                                visible:            rtkGrid.rtkSettings.surveyInMinObservationDuration.visible
                                enabled:            !rtkGrid.useFixedPosition
                            }
                            FactTextField {
                                fact:               rtkGrid.rtkSettings.surveyInMinObservationDuration
                                visible:            rtkGrid.rtkSettings.surveyInMinObservationDuration.visible
                                enabled:            !rtkGrid.useFixedPosition
                                Layout.preferredWidth:  _valueFieldWidth
                            }

                            QGCRadioButton {
                                text:               qsTr("Use Specified Base Position")
                                visible:            rtkGrid.rtkSettings.useFixedBasePosition.visible
                                checked:            rtkGrid.rtkSettings.useFixedBasePosition.value === true
                                onClicked:          rtkGrid.rtkSettings.useFixedBasePosition.value = true
                                Layout.columnSpan:  3
                            }

                            Item { width: rtkGrid.firstColWidth; height: 1 }
                            QGCLabel {
                                text:               rtkGrid.rtkSettings.fixedBasePositionLatitude.shortDescription
                                visible:            rtkGrid.rtkSettings.fixedBasePositionLatitude.visible
                                enabled:            rtkGrid.useFixedPosition
                            }
                            FactTextField {
                                fact:               rtkGrid.rtkSettings.fixedBasePositionLatitude
                                visible:            rtkGrid.rtkSettings.fixedBasePositionLatitude.visible
                                enabled:            rtkGrid.useFixedPosition
                                Layout.fillWidth:   true
                            }

                            Item { width: rtkGrid.firstColWidth; height: 1 }
                            QGCLabel {
                                text:               rtkGrid.rtkSettings.fixedBasePositionLongitude.shortDescription
                                visible:            rtkGrid.rtkSettings.fixedBasePositionLongitude.visible
                                enabled:            rtkGrid.useFixedPosition
                            }
                            FactTextField {
                                fact:               rtkGrid.rtkSettings.fixedBasePositionLongitude
                                visible:            rtkGrid.rtkSettings.fixedBasePositionLongitude.visible
                                enabled:            rtkGrid.useFixedPosition
                                Layout.fillWidth:   true
                            }

                            Item { width: rtkGrid.firstColWidth; height: 1 }
                            QGCLabel {
                                text:           rtkGrid.rtkSettings.fixedBasePositionAltitude.shortDescription
                                visible:        rtkGrid.rtkSettings.fixedBasePositionAltitude.visible
                                enabled:        rtkGrid.useFixedPosition
                            }
                            FactTextField {
                                fact:               rtkGrid.rtkSettings.fixedBasePositionAltitude
                                visible:            rtkGrid.rtkSettings.fixedBasePositionAltitude.visible
                                enabled:            rtkGrid.useFixedPosition
                                Layout.fillWidth:   true
                            }

                            Item { width: rtkGrid.firstColWidth; height: 1 }
                            QGCLabel {
                                text:           rtkGrid.rtkSettings.fixedBasePositionAccuracy.shortDescription
                                visible:        rtkGrid.rtkSettings.fixedBasePositionAccuracy.visible
                                enabled:        rtkGrid.useFixedPosition
                            }
                            FactTextField {
                                fact:               rtkGrid.rtkSettings.fixedBasePositionAccuracy
                                visible:            rtkGrid.rtkSettings.fixedBasePositionAccuracy.visible
                                enabled:            rtkGrid.useFixedPosition
                                Layout.fillWidth:   true
                            }

                            Item { width: rtkGrid.firstColWidth; height: 1 }
                            QGCButton {
                                text:               qsTr("Save Current Base Position")
                                enabled:            QGroundControl.gpsRtk && QGroundControl.gpsRtk.valid.value
                                Layout.columnSpan:  2
                                onClicked: {
                                    rtkGrid.rtkSettings.fixedBasePositionLatitude.rawValue =    QGroundControl.gpsRtk.currentLatitude.rawValue
                                    rtkGrid.rtkSettings.fixedBasePositionLongitude.rawValue =   QGroundControl.gpsRtk.currentLongitude.rawValue
                                    rtkGrid.rtkSettings.fixedBasePositionAltitude.rawValue =    QGroundControl.gpsRtk.currentAltitude.rawValue
                                    rtkGrid.rtkSettings.fixedBasePositionAccuracy.rawValue =    QGroundControl.gpsRtk.currentAccuracy.rawValue
                                }
                            }
                        }
                    }

                    Item { width: 1; height: _margins; visible: adsbCard.visible }
                    Rectangle {
                        id:                     adsbCard
                        Layout.preferredHeight: adsbGrid.y + adsbGrid.height + _margins
                        Layout.preferredWidth:  adsbGrid.width + (_margins * 2)
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        visible:                QGroundControl.settingsManager.adsbVehicleManagerSettings.visible
                        Layout.fillWidth:       true

                        QGCLabel {
                            id:                 adsbSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("ADSB Server")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        QGCLabel {
                            id:                 warningLabel
                            anchors.margins:    _margins
                            anchors.top:        adsbSectionLabel.bottom
                            anchors.left:       parent.left
                            anchors.right:      parent.right
                            font.pointSize:     ScreenTools.smallFontPointSize
                            wrapMode:           Text.WordWrap
                            text:               qsTr("Note: These setting are not meant for use with an ADSB transponder which is situated on the vehicle.")
                        }

                        GridLayout {
                            id:                         adsbGrid
                            anchors.margins:            _margins
                            anchors.top:                warningLabel.bottom
                            Layout.fillWidth:           true
                            anchors.left:               parent.left
                            columns:                    2
                            columnSpacing:              _margins

                            property var  adsbSettings:    QGroundControl.settingsManager.adsbVehicleManagerSettings

                            FactCheckBox {
                                text:                   adsbGrid.adsbSettings.adsbServerConnectEnabled.shortDescription
                                fact:                   adsbGrid.adsbSettings.adsbServerConnectEnabled
                                visible:                adsbGrid.adsbSettings.adsbServerConnectEnabled.visible
                                Layout.columnSpan:      2
                            }

                            QGCLabel {
                                text:                   adsbGrid.adsbSettings.adsbServerHostAddress.shortDescription
                                visible:                adsbGrid.adsbSettings.adsbServerHostAddress.visible
                                Layout.minimumWidth:    _labelWidth
                            }
                            FactTextField {
                                fact:                   adsbGrid.adsbSettings.adsbServerHostAddress
                                visible:                adsbGrid.adsbSettings.adsbServerHostAddress.visible
                                Layout.preferredWidth:  _valueFieldWidth
                            }

                            QGCLabel {
                                text:               adsbGrid.adsbSettings.adsbServerPort.shortDescription
                                visible:            adsbGrid.adsbSettings.adsbServerPort.visible
                            }
                            FactTextField {
                                fact:                   adsbGrid.adsbSettings.adsbServerPort
                                visible:                adsbGrid.adsbSettings.adsbServerPort.visible
                                Layout.preferredWidth:  _valueFieldWidth
                            }
                        }
                    }

                    Item { width: 1; height: _margins; visible: brandImageCard.visible }
                    Rectangle {
                        id:                     brandImageCard
                        Layout.preferredWidth:  brandImageGrid.width + (_margins * 2)
                        Layout.preferredHeight: brandImageGrid.y + brandImageGrid.height + _margins
                        Layout.fillWidth:       true
                        color:                  qgcPal.windowShade
                        radius:                 _cardRadius
                        border.width:           1
                        border.color:           _cardBorderColor
                        visible:                QGroundControl.settingsManager.brandImageSettings.visible && !ScreenTools.isMobile

                        QGCLabel {
                            id:                 brandImageSectionLabel
                            anchors.margins:    _margins
                            anchors.top:        parent.top
                            anchors.left:       parent.left
                            text:               qsTr("Brand Image")
                            font.bold:          true
                            font.pointSize:     _cardTitlePointSize
                        }

                        GridLayout {
                            id:                 brandImageGrid
                            anchors.margins:    _margins
                            anchors.top:        brandImageSectionLabel.bottom
                            anchors.left:       parent.left
                            anchors.right:      parent.right
                            columns:            3
                            columnSpacing:      _margins

                            QGCLabel {
                                text:                   qsTr("Indoor Image")
                                visible:                _userBrandImageIndoor.visible
                                Layout.minimumWidth:    _labelWidth
                            }
                            QGCTextField {
                                readOnly:           true
                                Layout.fillWidth:   true
                                text:               _userBrandImageIndoor.valueString.replace("file:///","")
                            }
                            QGCButton {
                                text:       qsTr("Browse")
                                onClicked:  userBrandImageIndoorBrowseDialog.openForLoad()
                                QGCFileDialog {
                                    id:                 userBrandImageIndoorBrowseDialog
                                    title:              qsTr("Choose custom brand image file")
                                    folder:             _userBrandImageIndoor.rawValue.replace("file:///","")
                                    selectExisting:     true
                                    selectFolder:       false
                                    onAcceptedForLoad:  _userBrandImageIndoor.rawValue = "file:///" + file
                                }
                            }

                            QGCLabel {
                                text:       qsTr("Outdoor Image")
                                visible:    _userBrandImageOutdoor.visible
                            }
                            QGCTextField {
                                readOnly:           true
                                Layout.fillWidth:   true
                                text:                _userBrandImageOutdoor.valueString.replace("file:///","")
                            }
                            QGCButton {
                                text:       qsTr("Browse")
                                onClicked:  userBrandImageOutdoorBrowseDialog.openForLoad()
                                QGCFileDialog {
                                    id:                 userBrandImageOutdoorBrowseDialog
                                    title:              qsTr("Choose custom brand image file")
                                    folder:             _userBrandImageOutdoor.rawValue.replace("file:///","")
                                    selectExisting:     true
                                    selectFolder:       false
                                    onAcceptedForLoad:  _userBrandImageOutdoor.rawValue = "file:///" + file
                                }
                            }
                            QGCButton {
                                text:               qsTr("Reset Default Brand Image")
                                Layout.columnSpan:  3
                                Layout.alignment:   Qt.AlignLeft
                                onClicked:  {
                                    _userBrandImageIndoor.rawValue = ""
                                    _userBrandImageOutdoor.rawValue = ""
                                }
                            }
                        }
                    }

                    Item { width: 1; height: _margins }
                    QGCLabel {
                        text:               qsTr("%1 Version").arg(QGroundControl.appName)
                        Layout.alignment:   Qt.AlignLeft
                    }
                    QGCLabel {
                        text:               QGroundControl.qgcVersion
                        Layout.alignment:   Qt.AlignLeft
                    }
                } // settingsColumn
            }
    }
}
