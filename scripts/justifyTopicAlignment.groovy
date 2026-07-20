// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2270

// @ExecutionModes({ON_SINGLE_NODE})
import org.freeplane.view.swing.map.NodeView
import org.freeplane.features.mode.Controller
import org.freeplane.features.map.INodeSelectionListener
import org.freeplane.features.map.NodeModel
import groovy.transform.Field

@Field NodeModel currentlySelectedNode = Controller.currentController.MapViewManager.mapView.mapSelection.selectionRoot

INodeSelectionListener mySelectionListener = new INodeSelectionListener() {
    @Override
    public void onDeselect(NodeModel node) {
    }

    @Override
    public void onSelect(NodeModel node) {
        if (node == currentlySelectedNode) {
            return
        }
        currentlySelectedNode = node

        //
        // Configuration
        //
        def width_grid = 20      // Grid: increments of 20 pixels
        def width_offset = 10    // Offset to be added
        def zoomfactor = c.getZoom()

        //
        // Group nodes by level (only visible nodes)
        //
        def nodesByLevel = [:].withDefault { [] }
        c.findAll().each { n ->
            // Ignore nodes within folded branches (except the node itself)
            def insideFoldedBranch = n.pathToRoot.dropRight(1).any { it.folded }
            // def insideFoldedBranch = false
            if (!insideFoldedBranch) {
                def level = n.getNodeLevel(false)
                nodesByLevel[level] << n
            }
        }

        //
        // Step 1: Reset minNodeWidth to -1 on all nodes,
        // allowing them to return to their natural size
        //
        nodesByLevel.each { level, nodesInLevel ->
            nodesInLevel.each { n ->
                n.style.minNodeWidth = -1
            }
        }

        // Force layout update
        Controller.getCurrentController().getMap().rootNode.viewers[0].updateAll()
        // Wait 200 milliseconds for the interface to update
        Thread.sleep(200)

        //
        // Step 2: For each level, calculate the maximum natural width and apply the new minNodeWidth
        //
        nodesByLevel.each { level, nodesInLevel ->
            def maxwidth = 0
            // Iterate over the nodes at this level to determine the largest natural width
            nodesInLevel.each { n ->
                def nodeView = n.delegate.viewers.find { it instanceof NodeView }
                // Use getPreferredSize() to obtain the natural width of the component
                def width = nodeView?.mainView?.getPreferredSize()?.width ?: 0
                // def width = nodeView?.mainView?.width ?: 0
                if (width > maxwidth) {
                    maxwidth = width
                }
            }

            // Adjust the value to the grid
            def widthcompare = width_grid
            while (maxwidth.div(zoomfactor) + width_offset > widthcompare) {
                widthcompare += width_grid
            }

            // Apply the new minNodeWidth to all nodes at this level
            nodesInLevel.each { n ->
                n.style.minNodeWidth = widthcompare
            }
        }

    }
}

Controller.currentController.modeController.mapController.addNodeSelectionListener(mySelectionListener)
