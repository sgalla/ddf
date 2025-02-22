/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
/*global define, alert, setTimeout*/
const Marionette = require('marionette')
const _ = require('underscore')
const template = require('./filter.hbs')
const CustomElements = require('../../js/CustomElements.js')
const FilterComparatorDropdownView = require('../dropdown/filter-comparator/dropdown.filter-comparator.view.js')
const MultivalueView = require('../multivalue/multivalue.view.js')
const metacardDefinitions = require('../singletons/metacard-definitions.js')
const PropertyModel = require('../property/property.js')
const DropdownModel = require('../dropdown/dropdown.js')
const DropdownView = require('../dropdown/dropdown.view.js')
const RelativeTimeView = require('../relative-time/relative-time.view.js')
const BetweenTimeView = require('../between-time/between-time.view.js')
const ValueModel = require('../value/value.js')
const CQLUtils = require('../../js/CQLUtils.js')
const properties = require('../../js/properties.js')
const Common = require('../../js/Common.js')
import {
  geometryComparators,
  dateComparators,
  stringComparators,
  numberComparators,
  booleanComparators,
} from './comparators'

const generatePropertyJSON = (value, type, comparator) => {
  const propertyJSON = _.extend({}, metacardDefinitions.metacardTypes[type], {
    value: value,
    multivalued: false,
    enumFiltering: true,
    enumCustom: true,
    matchcase: ['MATCHCASE', '='].indexOf(comparator) !== -1 ? true : false,
    enum: metacardDefinitions.enums[type],
    showValidationIssues: false,
  })

  if (propertyJSON.type === 'GEOMETRY') {
    propertyJSON.type = 'LOCATION'
  }

  if (propertyJSON.type === 'STRING') {
    propertyJSON.placeholder = 'Use * for wildcard.'
  }

  if (comparator === 'NEAR') {
    propertyJSON.type = 'NEAR'
    propertyJSON.param = 'within'
    propertyJSON.help =
      'The distance (number of words) within which search terms must be found in order to match'
    delete propertyJSON.enum
  }

  // if we don't set this the property model will transform the value as if it's a date, clobbering the special format
  if (comparator === 'RELATIVE' || comparator === 'BETWEEN') {
    propertyJSON.transformValue = false
  }

  return propertyJSON
}

const determineView = comparator => {
  let necessaryView
  switch (comparator) {
    case 'RELATIVE':
      necessaryView = RelativeTimeView
      break
    case 'BETWEEN':
      necessaryView = BetweenTimeView
      break
    default:
      necessaryView = MultivalueView
      break
  }
  return necessaryView
}

function comparatorToCQL() {
  return {
    BEFORE: 'BEFORE',
    AFTER: 'AFTER',
    RELATIVE: '=',
    BETWEEN: 'DURING',
    INTERSECTS: 'INTERSECTS',
    CONTAINS: 'ILIKE',
    MATCHCASE: 'LIKE',
    EQUALS: '=',
    '>': '>',
    '<': '<',
    '=': '=',
    '<=': '<=',
    '>=': '>=',
  }
}

module.exports = Marionette.LayoutView.extend({
  template: template,
  tagName: CustomElements.register('filter'),
  attributes: function() {
    return { 'data-id': this.model.cid }
  },
  events: {
    'click > .filter-remove': 'delete',
  },
  modelEvents: {},
  regions: {
    filterRearrange: '.filter-rearrange',
    filterAttribute: '.filter-attribute',
    filterComparator: '.filter-comparator',
    filterInput: '.filter-input',
  },
  initialize: function() {
    this.listenTo(this.model, 'change:type', this.updateTypeDropdown)
    this.listenTo(this.model, 'change:type', this.determineInput)
    this.listenTo(this.model, 'change:value', this.determineInput)
    this.listenTo(this.model, 'change:comparator', this.determineInput)
  },
  onBeforeShow: function() {
    this.$el.toggleClass('is-sortable', this.options.isSortable || true)
    this.filterAttribute.show(
      DropdownView.createSimpleDropdown({
        list: metacardDefinitions.sortedMetacardTypes
          .filter(function(metacardType) {
            return !properties.isHidden(metacardType.id)
          })
          .filter(function(metacardType) {
            return !metacardDefinitions.isHiddenType(metacardType.id)
          })
          .map(function(metacardType) {
            return {
              label: metacardType.alias || metacardType.id,
              description: (properties.attributeDescriptions || {})[
                metacardType.id
              ],
              value: metacardType.id,
            }
          }),
        defaultSelection: [this.model.get('type') || 'anyText'],
        hasFiltering: true,
      })
    )
    this.listenTo(
      this.filterAttribute.currentView.model,
      'change:value',
      this.handleAttributeUpdate
    )
    this._filterDropdownModel = new DropdownModel({
      value: this.model.get('comparator') || 'CONTAINS',
    })
    this.filterComparator.show(
      new FilterComparatorDropdownView({
        model: this._filterDropdownModel,
        modelForComponent: this.model,
      })
    )
    this.determineInput()
  },
  transformValue: function(value, comparator) {
    switch (comparator) {
      case 'NEAR':
        if (value[0].constructor !== Object) {
          value[0] = {
            value: value[0],
            distance: 2,
          }
        }
        break
      case 'INTERSECTS':
      case 'DWITHIN':
        break
      default:
        if (value === null || value[0] === null) {
          value = ['']
          break
        }
        if (value[0].constructor === Object) {
          value[0] = value[0].value
        }
        break
    }
    return value
  },
  // With the relative date comparator being the same as =, we need to try and differentiate them this way
  updateTypeDropdown: function() {
    const attribute = this.model.get('type')
    if (attribute === 'anyGeo') {
      this.model.set('comparator', [geometryComparators[1]])
    } else if (attribute === 'anyText') {
      this.model.set('comparator', [stringComparators[1]])
    }
    this.filterAttribute.currentView.model.set('value', [attribute])
  },
  handleAttributeUpdate: function() {
    const previousAttributeType =
      metacardDefinitions.metacardTypes[this.model.get('type')].type
    this.model.set(
      'type',
      this.filterAttribute.currentView.model.get('value')[0]
    )
    const currentAttributeType =
      metacardDefinitions.metacardTypes[this.model.get('type')].type
    if (currentAttributeType !== previousAttributeType) {
      this.model.set('value', [''])
    }
  },
  delete: function() {
    this.model.destroy()
  },
  toggleLocationClass: function(toggle) {
    this.$el.toggleClass('is-location', toggle)
  },
  toggleDateClass: function(toggle) {
    this.$el.toggleClass('is-date', toggle)
  },
  setDefaultComparator: function(propertyJSON) {
    this.toggleLocationClass(false)
    this.toggleDateClass(false)
    var currentComparator = this.model.get('comparator')
    switch (propertyJSON.type) {
      case 'LOCATION':
        if (geometryComparators.indexOf(currentComparator) === -1) {
          this.model.set('comparator', 'INTERSECTS')
        }
        this.toggleLocationClass(currentComparator !== 'IS EMPTY')
        break
      case 'DATE':
        if (dateComparators.indexOf(currentComparator) === -1) {
          this.model.set('comparator', 'BEFORE')
        }
        this.toggleDateClass(true)
        break
      case 'BOOLEAN':
        if (booleanComparators.indexOf(currentComparator) === -1) {
          this.model.set('comparator', '=')
        }
        break
      case 'LONG':
      case 'DOUBLE':
      case 'FLOAT':
      case 'INTEGER':
      case 'SHORT':
        if (numberComparators.indexOf(currentComparator) === -1) {
          this.model.set('comparator', '>')
        }
        break
      default:
        if (stringComparators.indexOf(currentComparator) === -1) {
          this.model.set('comparator', 'CONTAINS')
        }
        break
    }
  },
  updateValueFromInput: function() {
    if (this.filterInput.currentView) {
      const value = Common.duplicate(
        this.filterInput.currentView.model.getValue()
      )
      const isValid = this.filterInput.currentView.isValid()
      this.model.set({ value, isValid }, { silent: true })
    }
  },
  determineInput: function() {
    this.updateValueFromInput()
    let value = Common.duplicate(this.model.get('value'))
    const currentComparator = this.model.get('comparator')
    value = this.transformValue(value, currentComparator)
    const type = this.model.get('type')
    const propertyJSON = generatePropertyJSON(value, type, currentComparator)
    if (this.options.suggester && propertyJSON.enum === undefined) {
      this.options.suggester(propertyJSON).then(suggestions => {
        if (suggestions.length > 0) {
          propertyJSON.enum = suggestions.map(label => ({
            label,
            value: label,
          }))
          const ViewToUse = determineView(currentComparator)
          this.filterInput.show(
            new ViewToUse({
              model: new PropertyModel(propertyJSON),
            })
          )
          this.turnOnEditing()
        }
      })
    }
    const ViewToUse = determineView(currentComparator)
    const model = new PropertyModel(propertyJSON)
    this.listenTo(model, 'change:value', this.updateValueFromInput)
    this.filterInput.show(
      new ViewToUse({
        model: model,
      })
    )

    var isEditing = this.$el.hasClass('is-editing')
    if (isEditing || this.options.editing) {
      this.turnOnEditing()
    } else {
      this.turnOffEditing()
    }
    this.$el.toggleClass(
      'is-empty',
      this.model.get('comparator') === 'IS EMPTY'
    )
    this.setDefaultComparator(propertyJSON)
  },
  getValue: function() {
    var text = '('
    text += this.model.get('type') + ' '
    text += comparatorToCQL()[this.model.get('comparator')] + ' '
    text += this.filterInput.currentView.model.getValue()
    text += ')'
    return text
  },
  onDestroy: function() {
    this._filterDropdownModel.destroy()
  },
  turnOnEditing: function() {
    this.$el.addClass('is-editing')
    this.filterAttribute.currentView.turnOnEditing()
    this.filterComparator.currentView.turnOnEditing()

    var property =
      this.filterInput.currentView.model instanceof ValueModel
        ? this.filterInput.currentView.model.get('property')
        : this.filterInput.currentView.model
    property.set('isEditing', true)
  },
  turnOffEditing: function() {
    this.$el.removeClass('is-editing')
    this.filterAttribute.currentView.turnOffEditing()
    this.filterComparator.currentView.turnOffEditing()

    var property =
      this.filterInput.currentView.model instanceof ValueModel
        ? this.filterInput.currentView.model.get('property')
        : this.filterInput.currentView.model
    property.set(
      'isEditing',
      this.options.isForm === true || this.options.isFormBuilder === true
    )
  },
})
