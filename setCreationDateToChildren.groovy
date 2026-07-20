// SPDX-License-Identifier: CC0-1.0
// Discussion thread: https://github.com/freeplane/freeplane/discussions/1662

// @ExecutionModes({ON_SINGLE_NODE})

import javax.swing.JOptionPane
import java.text.SimpleDateFormat

def myNodes = [] + c.selecteds

// Default date to be shown in the text box
String defaultDate
if (node.mindMap.storage['lastDateEntered'] == null) {
    defaultDate = "2024-01-01"
}
else {
    defaultDate = node.mindMap.storage['lastDateEntered']
}

// Display input dialog with default date
String inputDate = JOptionPane.showInputDialog(null, "Enter the date in format yyyy-MM-dd", defaultDate)

// Check if the user has entered a date
if (inputDate != null && !inputDate.isEmpty()) {
    try {
        // Parse the user input to a Date object
        Date newDate = new SimpleDateFormat("yyyy-MM-dd").parse(inputDate)

        // Set the creation date of the current node branch
        myNodes.each{
            it.findAll().each {it.setCreatedAt(newDate)}
        }
        node.mindMap.storage['lastDateEntered'] = inputDate
    } catch (Exception e) {
        // Show error message if the date format is incorrect
        JOptionPane.showMessageDialog(null, "Invalid date format. Please enter the date in format yyyy-MM-dd.", "Error", JOptionPane.ERROR_MESSAGE)
    }
} else {
    // Show message if user cancels or enters no date
    JOptionPane.showMessageDialog(null, "No date entered.", "Info", JOptionPane.INFORMATION_MESSAGE)
}
