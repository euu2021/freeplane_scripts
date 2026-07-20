// SPDX-License-Identifier: MIT

c.select(c.selecteds.collect{it.children.findAll{it.visible}}.flatten())
