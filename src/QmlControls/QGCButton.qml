import QtQuick                  2.3
import QtQuick.Controls         2.12
import QtQuick.Controls.Styles  1.4

import QGroundControl.Palette 1.0
import QGroundControl.ScreenTools 1.0

Button {
    id:             control
    hoverEnabled:   true
    topPadding:     _verticalPadding
    bottomPadding:  _verticalPadding
    leftPadding:    _horizontalPadding
    rightPadding:   _horizontalPadding
    focusPolicy:    Qt.ClickFocus

    property bool   primary:        false                               ///< primary button for a group of buttons
    property real   pointSize:      ScreenTools.defaultFontPointSize    ///< Point size for button text
    property bool   showBorder:     qgcPal.globalTheme === QGCPalette.Light
    property bool   iconLeft:       false
    property real   backRadius:     0
    property real   heightFactor:   0.5
    property string iconSource

    property alias wrapMode:            text.wrapMode
    property alias horizontalAlignment: text.horizontalAlignment

    property bool   _showHighlight:     pressed | hovered | checked

    property int _horizontalPadding:    ScreenTools.defaultFontPixelWidth
    property int _verticalPadding:      Math.round(ScreenTools.defaultFontPixelHeight * heightFactor)

    QGCPalette { id: qgcPal; colorGroupEnabled: enabled }

    // Dhaksha skin: a raised, 3D button. The face is lit from above (lighter at the top, darker at the
    // bottom) and stands on a dark edge; pressing it pushes the face down onto the edge.
    readonly property real  _depth:     Math.max(2, Math.round(ScreenTools.defaultFontPixelHeight * 0.18))
    readonly property bool  _down:      pressed || checked
    readonly property color _faceColor: _showHighlight ?
                                            qgcPal.buttonHighlight :
                                            (primary ? qgcPal.primaryButton : qgcPal.button)

    transform: Translate { y: control._down ? control._depth : 0 }

    background: Item {
        implicitWidth:  ScreenTools.implicitButtonWidth
        implicitHeight: ScreenTools.implicitButtonHeight

        // The edge the face stands on; hidden under the face while the button is down
        Rectangle {
            anchors.fill:       parent
            anchors.topMargin:  control._depth
            anchors.bottomMargin: control._down ? 0 : -control._depth
            radius:             backRect.radius
            color:              Qt.darker(control._faceColor, 2.4)
            visible:            control.enabled
        }

        Rectangle {
            id:             backRect
            anchors.fill:   parent
            radius:         backRadius > 0 ? backRadius : Math.max(3, Math.round(ScreenTools.defaultFontPixelHeight * 0.25))
            border.width:   1
            border.color:   showBorder ? qgcPal.buttonBorder : Qt.darker(control._faceColor, 1.7)
            gradient: Gradient {
                GradientStop { position: 0.0;  color: Qt.lighter(control._faceColor, control._down ? 1.05 : 1.45) }
                GradientStop { position: 0.12; color: Qt.lighter(control._faceColor, control._down ? 1.0 : 1.15) }
                GradientStop { position: 0.6;  color: control._faceColor }
                GradientStop { position: 1.0;  color: Qt.darker(control._faceColor, control._down ? 1.05 : 1.3) }
            }
        }
    }

    contentItem: Item {
        implicitWidth:  text.implicitWidth + icon.width
        implicitHeight: text.implicitHeight
        baselineOffset: text.y + text.baselineOffset

        QGCColoredImage {
            id:                     icon
            source:                 control.iconSource
            height:                 source === "" ? 0 : text.height
            width:                  height
            color:                  text.color
            fillMode:               Image.PreserveAspectFit
            sourceSize.height:      height
            anchors.left:           control.iconLeft ? parent.left : undefined
            anchors.leftMargin:     control.iconLeft ? ScreenTools.defaultFontPixelWidth : undefined
            anchors.right:          !control.iconLeft ? parent.right : undefined
            anchors.rightMargin:    !control.iconLeft ? ScreenTools.defaultFontPixelWidth : undefined
            anchors.verticalCenter: parent.verticalCenter
        }

        Text {
            id:                     text
            anchors.centerIn:       parent
            antialiasing:           true
            text:                   control.text
            font.pointSize:         pointSize
            font.family:            ScreenTools.normalFontFamily
            color:                  _showHighlight ?
                                        qgcPal.buttonHighlightText :
                                        (primary ? qgcPal.primaryButtonText : qgcPal.buttonText)
        }
    }
}
