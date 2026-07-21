// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later

c.select(c.selecteds.collect{it.children.findAll{it.visible}}.flatten())
