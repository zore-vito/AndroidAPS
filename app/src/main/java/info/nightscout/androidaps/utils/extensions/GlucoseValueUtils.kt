package info.nightscout.androidaps.utils.extensions

import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.database.entities.GlucoseValue
import info.nightscout.androidaps.utils.DecimalFormatter

fun GlucoseValue.valueToUnits(units: String): Double =
    if (units == Constants.MGDL) value
    else value * Constants.MGDL_TO_MMOLL

fun GlucoseValue.valueToUnitsString(units: String): String =
    if (units == Constants.MGDL) DecimalFormatter.to0Decimal(value)
    else DecimalFormatter.to1Decimal(value * Constants.MGDL_TO_MMOLL)
