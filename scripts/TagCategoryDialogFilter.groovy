// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/issues/2926

/***************************************************************************

 Tag Category Manager Filter — adds a live filter bar (with auto-expand) to the
 "Manage tag categories for map" dialog.

 The dialog opened by Edit -> Node properties -> Manage tag categories for map has
 no search/filter. With a large tag hierarchy (hundreds of tags) finding one is slow.
 This script docks a filter field at the top of that dialog; each keystroke filters
 the tag tree AND expands it, so matches inside collapsed branches are revealed.

 The dialog's tree is a FilterableJTree (same as the Tag panel): setFilter(predicate)
 prunes to matches + ancestors but never expands to reveal a match in a collapsed
 branch — so we expand the pruned tree ourselves after each filter. (Verified on a real
 53-tag map: filtering a nested tag left it at row -1; expanding revealed it.)

 We own the whole field here, so there is no timing race: one handler does
 setFilter + expandAll in order.

 Usage:
   - run                 -> opens the dialog if needed and docks the filter field (focus it)
   - type                -> filters the tag tree live; matches auto-expand
   - empty / Esc         -> removes the filter (full tree back)
   - the field's x       -> removes the filter bar from the dialog

 *****************************************************************/

// @ExecutionModes({ON_SINGLE_NODE="/menu_bar/euu"})

import groovy.transform.Field

import org.freeplane.features.mode.Controller

import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.util.function.Predicate


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ User settings ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

@Field String panelTextFontName = "Dialog"
@Field int panelTextFontSize = 14
@Field int liveFilterDelayMs = 150
@Field String searchFieldPlaceholder = "Filter tags…"
@Field String closeButtonSymbol = "✕"

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ User settings ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/

@Field final String BAR_NAME = "TagCategoryDialogFilterBar"
@Field final String FIELD_NAME = "TagCategoryDialogFilterField"

@Field JDialog dialog
@Field JTree tagTree
@Field JTextField searchField
@Field Timer liveFilterTimer
@Field String lastAppliedText = " "
@Field Font cachedItemFont


// 1) find the manage-tag-categories dialog; open it if needed
dialog = findTagDialog()
if (dialog == null) {
    def action = Controller.currentController.modeController.getAction("ManageTagCategoriesAction")
    if (action == null) {
        JOptionPane.showMessageDialog(null, "Could not find the 'Manage tag categories' action.",
                "Tag category filter", JOptionPane.WARNING_MESSAGE)
        return
    }
    action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "open"))
    dialog = findTagDialog()
}
if (dialog == null) {
    JOptionPane.showMessageDialog(null, "The tag category manager dialog did not open.",
            "Tag category filter", JOptionPane.WARNING_MESSAGE)
    return
}

tagTree = findTagTree(dialog)
if (tagTree == null) {
    JOptionPane.showMessageDialog(null, "Tag tree not found inside the dialog.",
            "Tag category filter", JOptionPane.WARNING_MESSAGE)
    return
}

// 2) already docked? just focus the field
JComponent existingField = (JComponent) findByName(dialog.getContentPane(), FIELD_NAME)
if (existingField != null) {
    existingField.requestFocusInWindow()
    ((JTextField) existingField).selectAll()
    return
}

liveFilterTimer = new Timer(liveFilterDelayMs, { ActionEvent e -> runFilter() } as ActionListener)
liveFilterTimer.setRepeats(false)

installBar()
return


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Bar / UI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void installBar() {
    searchField = new JTextField() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g)
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create()
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.setFont(getFont().deriveFont(Font.ITALIC))
                    g2.setColor(blend(getForeground(), getBackground(), 0.55f))
                    g2.drawString(searchFieldPlaceholder, getInsets().left,
                            getInsets().top + g2.getFontMetrics().getAscent())
                } finally {
                    g2.dispose()
                }
            }
        }
    }
    searchField.setName(FIELD_NAME)
    searchField.setFont(itemFont())
    searchField.setBorder(BorderFactory.createCompoundBorder(
            searchField.getBorder(), BorderFactory.createEmptyBorder(2, 6, 2, 6)))

    searchField.getDocument().addDocumentListener(new DocumentListener() {
        @Override
        void insertUpdate(DocumentEvent e) { liveFilterTimer.restart() }
        @Override
        void removeUpdate(DocumentEvent e) { liveFilterTimer.restart() }
        @Override
        void changedUpdate(DocumentEvent e) { liveFilterTimer.restart() }
    })
    // Esc clears the filter (does not close the dialog)
    searchField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear")
    searchField.getActionMap().put("clear", new AbstractAction() {
        @Override
        void actionPerformed(ActionEvent e) { searchField.setText(""); liveFilterTimer.stop(); runFilter() }
    })

    JLabel label = new JLabel(" Filter: ")
    label.setFont(itemFont())

    JButton closeButton = new JButton(closeButtonSymbol)
    closeButton.setFont(new Font(panelTextFontName, Font.BOLD, panelTextFontSize - 1))
    closeButton.setToolTipText("Remove the filter bar")
    closeButton.setMargin(new Insets(0, 4, 0, 4))
    closeButton.setFocusPainted(false)
    closeButton.addActionListener({ ActionEvent e -> removeBar() } as ActionListener)

    JPanel bar = new JPanel(new BorderLayout())
    bar.setName(BAR_NAME)
    bar.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4))
    bar.add(label, BorderLayout.WEST)
    bar.add(searchField, BorderLayout.CENTER)
    bar.add(closeButton, BorderLayout.EAST)

    // Dock at the top of the dialog. If the content pane's NORTH is taken, stack our bar
    // above the existing NORTH so nothing is displaced.
    Container content = dialog.getContentPane()
    if (content.getLayout() instanceof BorderLayout) {
        BorderLayout bl = (BorderLayout) content.getLayout()
        Component oldNorth = bl.getLayoutComponent(content, BorderLayout.NORTH)
        if (oldNorth != null) {
            content.remove(oldNorth)
            JPanel stack = new JPanel(new BorderLayout())
            stack.setName(BAR_NAME + "Stack")
            stack.add(bar, BorderLayout.NORTH)
            stack.add(oldNorth, BorderLayout.CENTER)
            content.add(stack, BorderLayout.NORTH)
        } else {
            content.add(bar, BorderLayout.NORTH)
        }
    } else {
        content.add(bar, BorderLayout.NORTH)
    }

    content.revalidate()
    content.repaint()
    searchField.requestFocusInWindow()
}

void removeBar() {
    if (liveFilterTimer != null) liveFilterTimer.stop()
    if (tagTree != null) tagTree.setFilter(null)   // restore full tree

    Container content = dialog.getContentPane()
    Component stack = findByName(content, BAR_NAME + "Stack")
    if (stack != null) {
        // unwrap: put the original NORTH back, drop our bar
        Container stackC = (Container) stack
        BorderLayout sbl = (BorderLayout) stackC.getLayout()
        Component oldNorth = sbl.getLayoutComponent(stackC, BorderLayout.CENTER)
        content.remove(stack)
        if (oldNorth != null) { stackC.remove(oldNorth); content.add(oldNorth, BorderLayout.NORTH) }
    } else {
        Component bar = findByName(content, BAR_NAME)
        if (bar != null) content.remove(bar)
    }
    content.revalidate()
    content.repaint()
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Bar / UI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Filter ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void runFilter() {
    if (searchField == null || tagTree == null) return
    String text = searchField.getText().trim()
    if (text == lastAppliedText) return
    lastAppliedText = text

    if (text.isEmpty()) {
        tagTree.setFilter(null)
        return
    }
    String needle = text.toLowerCase()
    Predicate<Object> predicate = { Object node -> node.toString().toLowerCase().contains(needle) } as Predicate
    tagTree.setFilter(predicate)
    // reveal matches that sit inside collapsed branches (setFilter alone doesn't expand)
    expandAll(tagTree)
}

void expandAll(JTree tree) {
    int i = 0
    while (i < tree.getRowCount()) {
        tree.expandRow(i)
        i++
    }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Filter ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Helpers ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// A showing JDialog that contains a JTagTree = the tag category manager.
JDialog findTagDialog() {
    for (Window w : Window.getWindows()) {
        if (w.isShowing() && w instanceof JDialog && findTagTree((Container) w) != null) {
            return (JDialog) w
        }
    }
    return null
}

JTree findTagTree(Container c) {
    for (Component comp : c.getComponents()) {
        if (comp.getClass().getName().endsWith("JTagTree")) return (JTree) comp
        if (comp instanceof Container) {
            JTree r = findTagTree((Container) comp)
            if (r != null) return r
        }
    }
    return null
}

Component findByName(Container c, String name) {
    for (Component comp : c.getComponents()) {
        if (name == comp.getName()) return comp
        if (comp instanceof Container) {
            Component r = findByName((Container) comp, name)
            if (r != null) return r
        }
    }
    return null
}

Color blend(Color base, Color tint, float ratio) {
    return new Color(
            (int) (base.getRed() + (tint.getRed() - base.getRed()) * ratio),
            (int) (base.getGreen() + (tint.getGreen() - base.getGreen()) * ratio),
            (int) (base.getBlue() + (tint.getBlue() - base.getBlue()) * ratio))
}

Font itemFont() {
    if (cachedItemFont == null) cachedItemFont = new Font(panelTextFontName, Font.PLAIN, panelTextFontSize)
    return cachedItemFont
}
