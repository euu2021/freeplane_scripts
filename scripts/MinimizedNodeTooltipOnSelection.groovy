// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Version: 1.1

/**
 * Minimized node tooltip on selection.
 *
 * Freeplane shows the full content of a minimized (shortened) node as a tooltip when the
 * mouse hovers over it. This script does the same when the selection reaches such a node
 * without the mouse, so the content can be read without hovering.
 *
 * The tooltip is the native one: it is built by the very same MainView.createToolTip() /
 * getToolTipText() the hover path uses, so styling, details, notes and attributes look
 * exactly as usual.
 *
 * Two things trigger it. Key presses inside the map view, which covers arrow navigation.
 * And selection changes themselves, which covers everything that moves the selection
 * without a key press: bookmarks, other scripts, panels. Watching the selection is what
 * makes it work no matter who moved it; watching key presses on top of that is what keeps
 * it responsive while holding an arrow key down.
 *
 * A selection caused by clicking is deliberately ignored — hovering already shows the
 * tooltip there, and popping a second one under the pointer is just noise. That is what
 * the mouse press grace period below is for.
 *
 * It disappears on the next selection change, on any mouse press, on mouse wheel scrolling
 * and when the window loses focus.
 *
 * Running the script installs it; running it again removes it (toggle). The status bar
 * reports which of the two happened.
 *
 * CHANGELOG
 * ---------
 *   1.1 (2026-07-29)
 *       Also triggers on selection changes, not only on key presses, so the tooltip now
 *       shows up when the selection is moved by a bookmark, a panel or another script.
 *       Clicking still does not raise it.
 *   1.0 (2026-07-28)
 *       First public version.
 */

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.AWTEventListenerProxy
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowEvent
import java.lang.ref.WeakReference

import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToolTip
import javax.swing.MenuSelectionManager
import javax.swing.Popup
import javax.swing.PopupFactory
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

import org.freeplane.core.resources.ResourceController
import org.freeplane.core.ui.components.UITools
import org.freeplane.features.map.INodeSelectionListener
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.features.text.TextController

class MinimizedNodeTooltipWatcher implements AWTEventListener, INodeSelectionListener {

    /** false shows the tooltip for every node that has one, not only for minimized ones. */
    static final boolean MINIMIZED_ONLY = true
    /** delay before the tooltip shows up, also used to coalesce bursts of key presses. */
    static final int SHOW_DELAY_MILLIS = 150
    /** second look, in case the map was still scrolling when the tooltip was placed. */
    static final int FOLLOW_UP_DELAY_MILLIS = 300
    /** 0 keeps the tooltip until something else hides it. */
    static final int AUTO_HIDE_MILLIS = 0
    /** a selection landing this soon after a mouse press is taken to be that click's own. */
    static final int MOUSE_GRACE_MILLIS = 250

    private static final String SHOW_NODE_TOOLTIPS = 'show_node_tooltips'

    private final javax.swing.Timer showTimer
    private final javax.swing.Timer followUpTimer
    private final javax.swing.Timer autoHideTimer

    private Popup popup
    private Component popupOwner
    private String popupText
    private Point popupLocation
    private WeakReference<NodeModel> lastSelection = new WeakReference<NodeModel>(null)
    private long lastMousePressAt = 0L

    MinimizedNodeTooltipWatcher() {
        showTimer = new javax.swing.Timer(SHOW_DELAY_MILLIS, { evaluateSelection(true) } as ActionListener)
        showTimer.setRepeats(false)
        followUpTimer = new javax.swing.Timer(FOLLOW_UP_DELAY_MILLIS, { evaluateSelection(false) } as ActionListener)
        followUpTimer.setRepeats(false)
        autoHideTimer = new javax.swing.Timer(Math.max(1, AUTO_HIDE_MILLIS), { hideTip() } as ActionListener)
        autoHideTimer.setRepeats(false)
    }

    @Override
    void eventDispatched(AWTEvent event) {
        int id = event.getID()
        if (event instanceof KeyEvent) {
            if (id == KeyEvent.KEY_PRESSED && isInsideMapView(((KeyEvent) event).getComponent())) {
                showTimer.restart()
            }
            return
        }
        if (event instanceof MouseWheelEvent) {
            hideTip()
            return
        }
        if (event instanceof MouseEvent) {
            if (id == MouseEvent.MOUSE_PRESSED) {
                lastMousePressAt = System.currentTimeMillis()
                showTimer.stop()
                followUpTimer.stop()
                hideTip()
                lastSelection = new WeakReference<NodeModel>(currentSelection())
            }
            return
        }
        if (event instanceof WindowEvent && id == WindowEvent.WINDOW_LOST_FOCUS) {
            hideTip()
        }
    }

    /**
     * The selection moved. The click case is skipped: selecting by mouse runs inside the
     * mouse press, so a selection arriving right after one belongs to that click, and the
     * hover tooltip already covers it.
     */
    @Override
    void onSelect(NodeModel selected) {
        if (System.currentTimeMillis() - lastMousePressAt < MOUSE_GRACE_MILLIS) {
            return
        }
        showTimer.restart()
    }

    @Override
    void onDeselect(NodeModel deselected) {
    }

    void uninstall() {
        showTimer.stop()
        followUpTimer.stop()
        hideTip()
    }

    private void evaluateSelection(boolean scheduleFollowUp) {
        NodeModel selected = currentSelection()
        NodeModel previous = lastSelection.get()
        if (selected == null) {
            hideTip()
            return
        }
        boolean selectionChanged = !selected.is(previous)
        lastSelection = new WeakReference<NodeModel>(selected)
        if (!selectionChanged && popup == null) {
            return
        }
        Component view = tooltipView(selected)
        if (view == null) {
            hideTip()
            return
        }
        String tipText = view.getToolTipText()
        if (tipText == null || tipText.isEmpty()) {
            hideTip()
            return
        }
        showTip(view, tipText)
        if (scheduleFollowUp) {
            followUpTimer.restart()
        }
    }

    private NodeModel currentSelection() {
        Controller controller = Controller.getCurrentController()
        return controller?.getSelection()?.getSelected()
    }

    private Component tooltipView(NodeModel node) {
        if (!ResourceController.getResourceController().getBooleanProperty(SHOW_NODE_TOOLTIPS)) {
            return null
        }
        if (isEditorActive() || isPopupMenuOpen()) {
            return null
        }
        Controller controller = Controller.getCurrentController()
        Component view = controller.getMapViewManager().getComponent(node)
        if (view == null || !view.isShowing()) {
            return null
        }
        if (MINIMIZED_ONLY) {
            TextController textController = TextController.getController(controller.getModeController())
            if (!textController.isMinimized(node, view)) {
                return null
            }
        }
        return view
    }

    private void showTip(Component view, String tipText) {
        JToolTip tip = view.createToolTip()
        tip.setTipText(tipText)
        Dimension preferred = tip.getPreferredSize()
        Rectangle screen = UITools.getAvailableScreenBounds(view)

        Point topLeft = new Point(0, 0)
        SwingUtilities.convertPointToScreen(topLeft, view)
        int viewTop = (int) topLeft.y
        int viewBottom = viewTop + view.getHeight() - 1
        int roomBelow = screen.y + screen.height - viewBottom
        int roomAbove = viewTop - screen.y

        int width = Math.min((int) preferred.width, screen.width)
        int height = (int) preferred.height
        int x = Math.max(screen.x, Math.min((int) topLeft.x, screen.x + screen.width - width))
        int y
        if (height <= roomBelow || roomBelow >= roomAbove) {
            height = Math.min(height, Math.max(1, roomBelow))
            y = viewBottom
        }
        else {
            height = Math.min(height, Math.max(1, roomAbove))
            y = viewTop - height
        }
        Point location = new Point(x, y)
        Dimension size = new Dimension(width, height)

        if (popup != null && view.is(popupOwner) && tipText == popupText && location.equals(popupLocation)) {
            return
        }
        hideTip()

        JPanel holder = new JPanel(new GridLayout(1, 1))
        holder.setBorder(javax.swing.BorderFactory.createEmptyBorder())
        holder.add((JComponent) tip)
        holder.setPreferredSize(size)
        popup = PopupFactory.getSharedInstance().getPopup(view, holder, x, y)
        popup.show()
        popupOwner = view
        popupText = tipText
        popupLocation = location
        if (AUTO_HIDE_MILLIS > 0) {
            autoHideTimer.restart()
        }
    }

    private void hideTip() {
        autoHideTimer.stop()
        if (popup != null) {
            popup.hide()
            popup = null
            popupOwner = null
            popupText = null
            popupLocation = null
        }
    }

    private static boolean isInsideMapView(Component component) {
        for (Component parent = component; parent != null; parent = parent.getParent()) {
            if (parent.getClass().getSimpleName() == 'MapView') {
                return true
            }
        }
        return false
    }

    private static boolean isEditorActive() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
        return focusOwner instanceof JTextComponent && isInsideMapView(focusOwner)
    }

    private static boolean isPopupMenuOpen() {
        return MenuSelectionManager.defaultManager().getSelectedPath().length > 0
    }
}

Toolkit toolkit = Toolkit.getDefaultToolkit()
def mapController = Controller.getCurrentController().getModeController().getMapController()

List<AWTEventListener> installed = []
toolkit.getAWTEventListeners().each { AWTEventListener listener ->
    AWTEventListener target = listener instanceof AWTEventListenerProxy ? ((AWTEventListenerProxy) listener).getListener() : listener
    if (target.getClass().getSimpleName() == MinimizedNodeTooltipWatcher.getSimpleName()) {
        installed << target
    }
}
installed.each { AWTEventListener old ->
    try {
        old.uninstall()
    }
    catch (Exception ignored) {
    }
    toolkit.removeAWTEventListener(old)
}

// compared by name, not by type: a recompiled script produces a different class, so the
// previous generation would not be recognised by instanceof
boolean hadSelectionWatcher = false
new ArrayList(mapController.getNodeSelectionListeners()).each { listener ->
    if (listener.getClass().getSimpleName() == MinimizedNodeTooltipWatcher.getSimpleName()) {
        mapController.removeNodeSelectionListener(listener)
        hadSelectionWatcher = true
    }
}

String message
if (installed.isEmpty() && !hadSelectionWatcher) {
    long mask = AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK
    MinimizedNodeTooltipWatcher watcher = new MinimizedNodeTooltipWatcher()
    toolkit.addAWTEventListener(watcher, mask)
    mapController.addNodeSelectionListener(watcher)
    message = 'Minimized node tooltip on selection: enabled'
}
else {
    message = 'Minimized node tooltip on selection: disabled'
}
Controller.getCurrentController().getViewController().out(message)
return message
