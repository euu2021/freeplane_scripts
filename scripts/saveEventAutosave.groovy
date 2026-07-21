// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2395

import org.freeplane.features.mode.Controller
import org.freeplane.features.map.mindmapmode.MMapModel
import javax.swing.Timer
import java.awt.event.ActionListener
import java.awt.event.ActionEvent

def mc = Controller.currentController
mapModel = mc.map as MMapModel

mapModel.enableAutosave()

Timer timer = mapModel.timerForAutomaticSaving
if (timer == null) {
    return
}

def originalListeners = timer.actionListeners.clone() as List
timer.stop()
originalListeners.each { timer.removeActionListener(it) }

timer.addActionListener(new ActionListener() {
    @Override
    void actionPerformed(ActionEvent e) {

        changeState = mapModel.getNumberOfChangesSinceLastSave();
        if (changeState == 0) {
            /* map was recently saved. */
            return;
        }

        // → PRE-AUTOSAVE
        println "my pre autosave code"

        originalListeners.each { it.actionPerformed(e) }

        // → POST-AUTOSAVE
        println "my post autosave code"

        // model.numberOfChangesSinceLastSave = 0 //uncomment to avoid autosaves when there were no changes since last autosave

        timer.start()
    }
})

timer.start()
