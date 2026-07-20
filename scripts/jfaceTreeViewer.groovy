// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2231

@Grab(group='org.swinglabs.swingx', module='swingx-core', version='1.6.5-1')

import javax.swing.*
import java.awt.Color
import java.awt.BorderLayout
import java.util.List
import org.freeplane.features.mode.Controller

import org.jdesktop.swingx.JXTreeTable
import org.jdesktop.swingx.treetable.DefaultMutableTreeTableNode
import org.jdesktop.swingx.treetable.DefaultTreeTableModel

// ----------------------------------------------------------------------------
// Function to Collect All Unique Attributes
// ----------------------------------------------------------------------------

/**
 * Traverses all nodes in the tree to collect unique attribute keys.
 *
 * @param node The current node to process.
 * @param uniqueKeys The set to store unique attribute keys.
 */
def collectUniqueAttributeKeys(node, uniqueKeys) {
    if (!node.attributes.empty) {
        uniqueKeys.addAll(node.attributes.names)
    }
    node.children.each { child ->
        collectUniqueAttributeKeys(child, uniqueKeys)
    }
}

// ----------------------------------------------------------------------------
// Custom Model Class for JXTreeTable
// ----------------------------------------------------------------------------

/**
 * Custom model for the JXTreeTable.
 * This model returns the node text and corresponding attributes for each column.
 */
class MyTreeTableModel extends DefaultTreeTableModel {
    List<String> columnNames

    MyTreeTableModel(DefaultMutableTreeTableNode root, List<String> columnNames) {
        super(root, columnNames as Object[])
        this.columnNames = columnNames
    }

    @Override
    Object getValueAt(Object node, int column) {
        if (node instanceof DefaultMutableTreeTableNode) {
            def userObject = node.userObject
            if (userObject instanceof List && column < userObject.size()) { // java.util.List
                return userObject[column]
            }
        }
        return null
    }

    @Override
    int getColumnCount() {
        return columnNames.size()
    }

    @Override
    String getColumnName(int column) {
        return columnNames[column]
    }
}

// ----------------------------------------------------------------------------
// Function to Convert Freeplane Nodes to TreeTable Nodes
// ----------------------------------------------------------------------------

/**
 * Converts a Freeplane node to a DefaultMutableTreeTableNode, including attributes.
 *
 * @param freeplaneNode The Freeplane node to convert.
 * @param columnNames List of column names, including "Process" and attributes.
 * @return The corresponding node for the JXTreeTable.
 */
def convertFreeplaneNode(freeplaneNode, columnNames) {
    // List to store row data
    def rowData = [freeplaneNode.text]

    // Add attribute values for each additional column
    columnNames.tail().each { key -> // Using tail() to avoid index errors
        def values = freeplaneNode.attributes.getAll(key)
        if (values && !values.isEmpty()) {
            rowData << values.join(", ")
        } else {
            rowData << ""
        }
    }

    // Create a TreeTable node with the row data
    def treeNode = new DefaultMutableTreeTableNode(rowData)

    // Recursively add child nodes
    freeplaneNode.children.each { child ->
        treeNode.add(convertFreeplaneNode(child, columnNames))
    }

    return treeNode
}

// ----------------------------------------------------------------------------
// Building the Tree Structure with Real Freeplane Data
// ----------------------------------------------------------------------------

// Get the current Freeplane controller
def controller = Controller.currentController

// Get the root node of the mind map correctly
def freeplaneRootNode = node.mindMap.root

// Check if the root node was obtained correctly
if (freeplaneRootNode == null) {
    println "Error: Could not obtain the root node of the mind map."
    return
}

// Collect all unique attributes in the tree
def uniqueKeys = new HashSet()
collectUniqueAttributeKeys(freeplaneRootNode, uniqueKeys)

// Define column names: "Process" + unique attributes
def columnNames = ["Process"] + uniqueKeys.sort()

// Convert the root node and its entire hierarchy to the TreeTable structure
def treeTableRoot = convertFreeplaneNode(freeplaneRootNode, columnNames)

// Create the custom model with defined columns
def model = new MyTreeTableModel(treeTableRoot, columnNames)

// Create the JXTreeTable instance with the model
def treeTable = new JXTreeTable(model)
treeTable.setRootVisible(true)
treeTable.setShowsRootHandles(true)
treeTable.setFillsViewportHeight(true)

// Configure colors to ensure visibility
treeTable.setBackground(Color.WHITE)
treeTable.setForeground(Color.BLACK)
treeTable.setSelectionForeground(Color.WHITE)
treeTable.setSelectionBackground(Color.BLUE)

// ----- Additional settings for gray grid lines -----

// Enable grid lines
treeTable.setShowGrid(true)

// Set grid color to gray
treeTable.setGridColor(Color.GRAY)

// (Optional) Adjust intercell spacing for better visibility
// treeTable.setIntercellSpacing(new Dimension(1, 1))

// Optional: Automatically adjust column widths
treeTable.packAll()

// ----------------------------------------------------------------------------
// Integration with Freeplane: Adding the TreeTable to the masterPanel
// ----------------------------------------------------------------------------

// Get the parent panel where the TreeTable will be added
def parentPanel = controller.mapViewManager.mapView.parent.parent as JScrollPane

// Create a new panel to contain the TreeTable
def treeTablePanel = new JPanel(new BorderLayout())
treeTablePanel.setOpaque(true) // Set as opaque to ensure visibility
treeTablePanel.setBackground(Color.WHITE) // Set background to white

// Add the TreeTable inside a JScrollPane
JScrollPane treeScrollPane = new JScrollPane(treeTable)
treeScrollPane.setOpaque(true)
treeScrollPane.getViewport().setOpaque(true)
treeScrollPane.setBorder(BorderFactory.createEmptyBorder()) // Remove borders if desired

// Add the JScrollPane to the treeTablePanel
treeTablePanel.add(treeScrollPane, BorderLayout.CENTER)

// ----------------------------------------------------------------------------
// Configuration of masterPanel and Final Integration
// ----------------------------------------------------------------------------

// Create the masterPanel that will contain the TreeTable
def masterPanel = new JPanel(new BorderLayout()) // Using BorderLayout for better control
masterPanel.setBounds(0, 0, 800, 600) // Adjust the size as needed
masterPanel.setOpaque(true) // Set as opaque to ensure visibility
masterPanel.setBackground(Color.WHITE) // Set background to white

// Add the treeTablePanel to the masterPanel
masterPanel.add(treeTablePanel, BorderLayout.CENTER)

// Make the masterPanel visible
masterPanel.setVisible(true)

// Add the masterPanel to the parentPanel
parentPanel.add(masterPanel)

// Set the z-order to ensure the masterPanel is on top
parentPanel.setComponentZOrder(masterPanel, 0)

// Update the interface to reflect changes
parentPanel.revalidate()
parentPanel.repaint()
