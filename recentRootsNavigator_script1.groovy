//script 1

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import org.freeplane.features.mode.Controller

import org.freeplane.features.map.NodeModel
import org.freeplane.plugin.script.proxy.ProxyFactory
import org.freeplane.plugin.script.proxy.ScriptUtils

import groovy.swing.SwingBuilder
import javax.swing.*
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.*
import java.util.List
import java.awt.event.*
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

currentlySelectedNodeId = ScriptUtils.node().id
c = ScriptUtils.c()
rootToSelect = node
frame = null
recentRoots = []
recentRootsNodeModels = []
lastSelectionPerRoot = [:]
loadSettings()

if (isCtrlPressed()) {
    showRecentRootsGUI()
}

else {
    reloadListOfRecentRoots()
    menuUtils.executeMenuItems([ 'JumpInAction' ])
}

def saveSettings() {
    File file = getSettingsFile()

    List<String> recentRootsIds = recentRoots.collect { it }

    String jsonString = new JsonBuilder([
            recentRootsLocations: recentRootsIds,
            lastSelectionPerRoot: lastSelectionPerRoot
    ]).toPrettyString()

    try {
        file.text = jsonString
    } catch (Exception e) {
        e.printStackTrace()
    }
}

File getSettingsFile(){
    File file = new File(
            c.getUserDirectory().toString()
                    + File.separator
                    + 'recentRootsConfig.json'
    )
}

private void loadSettings() {
    File file = getSettingsFile()

    if (!file.exists()) {
        try {
            saveSettings()
        } catch (Exception e) {
            e.printStackTrace()
        }
        return
    }
    try {
        String content = file.text
        def settings = new JsonSlurper().parseText(content)

        recentRoots = settings.recentRootsLocations ?: []
        lastSelectionPerRoot = settings.lastSelectionPerRoot ?: [:]
        recentRootsNodeModels = settings.recentRootsLocations.reverse().collect { id ->
            Controller.currentController.map.getNodeForID(id)
        }.findAll { it != null }

    } catch (Exception e) {
        e.printStackTrace()
    }
}

def showRecentRootsGUI() {
    def swing = new SwingBuilder()
    swing.edt {

        def jl = new JList<NodeModel>(recentRootsNodeModels as NodeModel[])
        jl.selectionMode = ListSelectionModel.SINGLE_SELECTION
        if (recentRootsNodeModels.size() > 1) {
            if (c.viewRoot == node.map.root) {
                jl.selectedIndex = 0
                jl.ensureIndexIsVisible(0)
            }
            else {
                jl.selectedIndex = 1
                jl.ensureIndexIsVisible(1)
            }
        }

        jl.setFocusTraversalKeysEnabled(false)
        jl.setFocusTraversalKeys(
                KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
                Collections.emptySet()
        )
        jl.setFocusTraversalKeys(
                KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
                Collections.emptySet()
        )

        jl.cellRenderer = { JList list, NodeModel value, int index, boolean isSelected, boolean cellHasFocus ->
            JPanel cell = new JPanel()
            cell.layout = new BoxLayout(cell, BoxLayout.Y_AXIS)
            cell.opaque = true
            cell.background = isSelected ? list.selectionBackground : list.background
            cell.border = new CompoundBorder(
                    new MatteBorder(0,0,1,0, Color.LIGHT_GRAY),
                    new EmptyBorder(4,4,4,4)
            )

            JLabel lblRoot = new JLabel(value.text)
            lblRoot.font = lblRoot.font.deriveFont(Font.BOLD, 20f)
            lblRoot.foreground = isSelected ? list.selectionForeground : list.foreground
            cell.add(lblRoot)

            String lastId = lastSelectionPerRoot[value.id]
            NodeModel lastNode = lastId ? Controller.currentController.map.getNodeForID(lastId) : null
            String lastText = lastNode?.text ?: value.text
            JLabel lblLast = new JLabel(lastText)
            lblLast.font = lblLast.font.deriveFont(Font.ITALIC, 18f)
            lblLast.foreground = isSelected ? list.selectionForeground : list.foreground
            cell.add(lblLast)

            return cell
        } as ListCellRenderer<NodeModel>

        Window owner = SwingUtilities.getWindowAncestor(Controller.currentController.getMapViewManager().getMapViewComponent())

        frame = frame(
                title: 'Recent Roots',
                size: [600, 800],
                locationRelativeTo: owner,
                defaultCloseOperation: WindowConstants.DISPOSE_ON_CLOSE,
                alwaysOnTop: true,
//                focusable: true,
                show: true
        ) {
            borderLayout()
            scrollPane(constraints: BorderLayout.CENTER) {
                widget jl
            }
        }

        def rp = frame.rootPane
        def im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        def am = rp.getActionMap()

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB,
                KeyEvent.CTRL_DOWN_MASK, false), "next")
        am.put("next", new AbstractAction() {
            void actionPerformed(ActionEvent e) {
                int idx = jl.selectedIndex
                idx = (idx + 1) % jl.model.size()
                jl.selectedIndex = idx
                jl.ensureIndexIsVisible(idx)
            }
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK, false),
                "previous")
        am.put("previous", new AbstractAction() {
            void actionPerformed(ActionEvent e) {
                int idx = jl.selectedIndex
                idx = (idx - 1 + jl.model.size()) % jl.model.size()
                jl.selectedIndex = idx
                jl.ensureIndexIsVisible(idx)
            }
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTROL,
                0, true), "activate")
        am.put("activate", new AbstractAction() {
            void actionPerformed(ActionEvent e) {
                int idx = jl.selectedIndex
                if (idx >= 0 && idx < recentRootsNodeModels.size()) {
                    onRootClick(recentRootsNodeModels[idx])
                }
                frame.dispose()
            }
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK, false), "clearBelow")
        am.put("clearBelow", new AbstractAction() {
            void actionPerformed(ActionEvent e) {
                int idx = jl.selectedIndex
                if (idx < 0) return

                def displayedIds = recentRootsNodeModels.collect { it.id }
                def toRemoveIds = []
                if (idx + 1 < displayedIds.size()) {
                    toRemoveIds = displayedIds[(idx+1)..<displayedIds.size()]
                }

                toRemoveIds.each { removeId ->
                    recentRoots.remove(removeId)
                    lastSelectionPerRoot.remove(removeId)
                }

                recentRootsNodeModels = recentRoots
                        .reverse()
                        .collect { id -> Controller.currentController.map.getNodeForID(id) }
                        .findAll { it != null }

                jl.setListData(recentRootsNodeModels as NodeModel[])

                if (idx < recentRootsNodeModels.size()) {
                    jl.selectedIndex = idx
                    jl.ensureIndexIsVisible(idx)
                }

                saveSettings()
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
}

def onRootClick (NodeModel nodeClicked) {
    updateMapOfSelectedNodePerRoot()

    newRootId = nodeClicked.id
    toSelectId = lastSelectionPerRoot[newRootId] ?: newRootId
    toSelectNode = Controller.currentController.map.getNodeForID(toSelectId)

    rootToSelect = nodeClicked
    Controller.currentController.mapViewManager.mapView.getMapSelection().selectAsTheOnlyOneSelected(nodeClicked)
    reloadListOfRecentRoots()
    menuUtils.executeMenuItems([ 'JumpInAction' ])
    try {
        Controller.currentController.mapViewManager.mapView.getMapSelection().selectAsTheOnlyOneSelected(toSelectNode)
    } catch (Exception e) {
        Controller.currentController.mapViewManager.mapView.getMapSelection().selectAsTheOnlyOneSelected(Controller.currentController.map.getNodeForID(newRootId))
    }
    if (c.viewRoot.id != toSelectId) menuUtils.executeMenuItems([ 'MoveSelectedNodeAction.CENTER' ])
    else menuUtils.executeMenuItems([ 'MoveSelectedNodeAction.LEFT' ])
}

def reloadListOfRecentRoots() {
    recentRoots = (recentRoots - rootToSelect.id << rootToSelect.id).takeRight(50)
    saveSettings()
}

def updateMapOfSelectedNodePerRoot () {
    currentRootId = c.viewRoot.id
    currentNodeId = currentlySelectedNodeId

    lastSelectionPerRoot[currentRootId] = currentNodeId
}

boolean isCtrlPressed() {
    AWTEvent ev = EventQueue.getCurrentEvent()
    return ev instanceof KeyEvent && ((KeyEvent) ev).isControlDown()
}