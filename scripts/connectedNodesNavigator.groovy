// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2575

import groovy.swing.SwingBuilder
import javax.swing.*
import java.awt.BorderLayout
import java.awt.event.*
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.proxy.ScriptUtils
import org.freeplane.features.map.NodeModel

def inCons = node.connectorsIn
def outCons = node.connectorsOut

// Create maps to track connection types
def connectionTypes = [:]
def nodeMap = [:]

// Process outgoing connectors
outCons.each { connector ->
    def targetNode = connector.target.delegate
    connectionTypes[targetNode] = connectionTypes.getOrDefault(targetNode, '') + 'out'
    nodeMap[targetNode] = connector.target
}

// Process incoming connectors
inCons.each { connector ->
    def sourceNode = connector.source.delegate
    connectionTypes[sourceNode] = connectionTypes.getOrDefault(sourceNode, '') + 'in'
    nodeMap[sourceNode] = connector.source
}

// Get the unique connected nodes
def connectedNodes = connectionTypes.keySet() as List

if (connectedNodes.isEmpty()) {
    JOptionPane.showMessageDialog(Controller.currentController.mapViewManager.mapView.parent.parent, "No connected nodes found.", "Info", JOptionPane.INFORMATION_MESSAGE)
    return
}

def swing = new SwingBuilder()
swing.edt {
    def listModel = new DefaultListModel<NodeModel>()
    connectedNodes.each { nodeModel ->
        listModel.addElement(nodeModel)
    }
    
    def jl = new JList<NodeModel>(listModel)
    jl.selectionMode = ListSelectionModel.SINGLE_SELECTION
    jl.selectedIndex = 0

    jl.cellRenderer = { JList list, NodeModel value, int index, boolean isSelected, boolean cellHasFocus ->
        def type = connectionTypes[value]
        def prefix = ''
        
        if (type.contains('in') && type.contains('out')) {
            prefix = 'in-out: '
        } else if (type.contains('in')) {
            prefix = 'in: '
        } else if (type.contains('out')) {
            prefix = 'out: '
        }
        
        JLabel label = new JLabel(prefix + value.text)
        label.background = isSelected ? list.selectionBackground : list.background
        label.foreground = isSelected ? list.selectionForeground : list.foreground
        label.opaque = true
        return label
    } as ListCellRenderer<NodeModel>

    def frame = swing.frame(
            title: 'Connected Nodes',
            size: [300, 400],
            locationRelativeTo: Controller.currentController.getMapViewManager().getMapViewComponent(),
            defaultCloseOperation: WindowConstants.DISPOSE_ON_CLOSE,
            alwaysOnTop: true,
            show: true
    ) {
        borderLayout()
        scrollPane(constraints: BorderLayout.CENTER) {
            widget jl
        }
    }

    jl.addMouseListener(new MouseAdapter() {
        @Override
        void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 1) {
                int index = jl.locationToIndex(e.getPoint())
                if (index >= 0) {
                    NodeModel selectedNode = jl.model.getElementAt(index)
                    Controller.currentController.mapViewManager.mapView.getMapSelection().selectAsTheOnlyOneSelected(selectedNode)
                    frame.dispose()
                }
            }
        }
    })

    jl.addKeyListener(new KeyAdapter() {
        @Override
        void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                NodeModel selectedNode = jl.selectedValue
                if (selectedNode != null) {
                    Controller.currentController.mapViewManager.mapView.getMapSelection().selectAsTheOnlyOneSelected(selectedNode)
                    frame.dispose()
                }
            }
        }
    })

    frame.addWindowFocusListener(new WindowAdapter() {
        @Override
        void windowLostFocus(WindowEvent e) {
            if (frame.isDisplayable()) {
                frame.dispose()
            }
        }
    })
}
