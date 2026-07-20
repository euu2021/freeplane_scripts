// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2475

import org.freeplane.api.NodeChangeListener
import org.freeplane.api.NodeChanged
import org.freeplane.features.icon.IconController
import org.freeplane.features.icon.Tag
import org.freeplane.features.icon.Tags
import org.freeplane.features.icon.mindmapmode.MIconController
import org.freeplane.features.mode.Controller
import org.freeplane.core.ui.components.UITools

import java.awt.Color

MIconController iconController = (MIconController) Controller.currentModeController.getExtension(IconController.class)

NodeChangeListener myNodeChangeListener= {NodeChanged event->
    if(event.changedElement == NodeChanged.ChangedElement.TAGS) {
        proxyNodeChanged = event.node

        nodeTags = iconController.getTags(proxyNodeChanged.delegate)
        nodeFirstTag = nodeTags[0]

        Color firstTagBackgroundColor = nodeFirstTag.getColor()
        Color firstTagFontColor = UITools.getTextColorForBackground(firstTagBackgroundColor)

        proxyNodeChanged.style.backgroundColor = firstTagBackgroundColor
        proxyNodeChanged.style.textColor = firstTagFontColor
    }
} as NodeChangeListener

mindMap.addListener(myNodeChangeListener)
