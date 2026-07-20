// SPDX-License-Identifier: CC0-1.0
// Discussion thread: https://github.com/freeplane/freeplane/issues/2732#issuecomment-3479925692

// Script to quickly adjust Note style font size with a slider
// @ExecutionModes({ON_SINGLE_NODE})

import org.freeplane.features.map.MapModel
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.features.styles.MapStyleModel
import org.freeplane.plugin.script.proxy.ScriptUtils
import org.freeplane.plugin.script.proxy.ProxyFactory
import javax.swing.*
import java.awt.*
import java.awt.event.*

def controller = Controller.currentController

// Get the style map and note style node
MapModel mapa = controller.map
MapStyleModel styleModel = MapStyleModel.getExtension(mapa)
MapModel styleMap = styleModel.getStyleMap()
NodeModel predefinedStylesParentNode = styleModel.getStyleNodeGroup(styleMap, MapStyleModel.STYLES_PREDEFINED)

def predefinedStyles = predefinedStylesParentNode.children
def noteStyle = predefinedStyles[4]
def noteStyleProxyNode = ProxyFactory.createNode(noteStyle, ScriptUtils.getCurrentContext())

// Get current font size
def currentFontSize = 12 // default
try {
    if (noteStyleProxyNode.style.font.size != null) {
        currentFontSize = noteStyleProxyNode.style.font.size
    }
} catch (Exception e) {
    // Use default if can't get current size
}

// Create dialog
def dialog = new JDialog(
    SwingUtilities.getWindowAncestor(controller.mapViewManager.mapView),
    "Adjust Note Font Size",
    Dialog.ModalityType.MODELESS
)

dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE)
dialog.setLayout(new BorderLayout(10, 10))

// Create main panel
def mainPanel = new JPanel()
mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS))
mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15))

// Title label
def titleLabel = new JLabel("Note Font Size")
titleLabel.setFont(new Font("Dialog", Font.BOLD, 14))
titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT)
mainPanel.add(titleLabel)
mainPanel.add(Box.createVerticalStrut(15))

// Size display label
def sizeLabel = new JLabel("Current size: ${currentFontSize} pt")
sizeLabel.setFont(new Font("Dialog", Font.PLAIN, 12))
sizeLabel.setAlignmentX(Component.CENTER_ALIGNMENT)
mainPanel.add(sizeLabel)
mainPanel.add(Box.createVerticalStrut(10))

// Create slider panel
def sliderPanel = new JPanel(new BorderLayout())

// Slider (range from 8 to 72)
def slider = new JSlider(JSlider.HORIZONTAL, 8, 72, currentFontSize as int)
slider.setMajorTickSpacing(16)
slider.setMinorTickSpacing(4)
slider.setPaintTicks(true)
slider.setPaintLabels(true)

// Update label and font size when slider changes
slider.addChangeListener { e ->
    def newSize = slider.getValue()
    sizeLabel.setText("Current size: ${newSize} pt")
    
    if (!slider.getValueIsAdjusting()) {
        // Apply the font size change
        try {
            noteStyleProxyNode.style.font.size = newSize
            controller.mapViewManager.mapView.repaint()
        } catch (Exception ex) {
            ui.errorMessage("Error applying font size: ${ex.message}")
        }
    }
}

sliderPanel.add(slider, BorderLayout.CENTER)
mainPanel.add(sliderPanel)
mainPanel.add(Box.createVerticalStrut(15))

// Button panel
def buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0))

// Reset button
def resetButton = new JButton("Reset to Default")
resetButton.addActionListener { e ->
    slider.setValue(12)
}

// Close button
def closeButton = new JButton("Close")
closeButton.addActionListener { e ->
    dialog.dispose()
}

buttonPanel.add(resetButton)
buttonPanel.add(closeButton)
mainPanel.add(buttonPanel)

dialog.add(mainPanel, BorderLayout.CENTER)

// Set dialog size and position
dialog.pack()
dialog.setMinimumSize(new Dimension(400, 200))
dialog.setLocationRelativeTo(controller.mapViewManager.mapView)

// Show dialog
dialog.setVisible(true)
