package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Gender
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.displayName

/**
 * Every field of a Publisher record, as one editable value. Add Publisher and Edit Publisher both build
 * their form from this, so the two always ask for exactly the same things.
 */
data class PublisherFormState(
    val firstName: String = "",
    val lastName: String = "",
    val middleInitial: String = "",
    val extensionName: String = "",
    val gender: Gender? = null,
    val contact: String = "",
    val contactPerson: String = "",
    val contactPersonNumber: String = "",
    /** The full street/house address. */
    val address: String = "",
    val province: String? = null,
    val cityMunicipality: String? = null,
    val barangay: String? = null,
    /** Kept as text so a half-typed "14." is allowed; see [latitude]/[longitude]. */
    val latitudeText: String = "",
    val longitudeText: String = "",
    val email: String = "",
    val category: PublisherCategory? = null,
    val groupId: String? = null,
    /** The congregation the publisher belongs to (the role assignment's congregation). */
    val congregationId: String? = null,
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,
    val remarks: String = "",
    /** Optional dates (UTC-midnight millis); Age and Baptismal Age are computed from them, never typed. */
    val birthdate: Long? = null,
    val baptismalDate: Long? = null,
) {
    /** Null when the dates are fine (blank is fine); otherwise the message to show. */
    val datesProblem: String? get() = com.emfitsolutions.gopreach.domain.PersonDates.problem(birthdate, baptismalDate)

    /** Everything that blocks saving: bad coordinates or invalid dates. */
    val formProblem: String? get() = coordinatesProblem ?: datesProblem

    val latitude: Double? get() = latitudeText.trim().toDoubleOrNull()
    val longitude: Double? get() = longitudeText.trim().toDoubleOrNull()

    /** Null when the coordinates are blank or a valid pair; otherwise what's wrong. */
    val coordinatesProblem: String?
        get() {
            if (latitudeText.isBlank() && longitudeText.isBlank()) return null
            val lat = latitude
            val lng = longitude
            return when {
                lat == null || lng == null -> "Coordinates must be numbers, e.g. 14.5995 and 120.9842."
                lat !in -90.0..90.0 || lng !in -180.0..180.0 -> "Latitude must be -90 to 90 and longitude -180 to 180."
                else -> null
            }
        }

    companion object {
        fun from(person: Person, category: PublisherCategory?, groupId: String?, congregationId: String? = null) = PublisherFormState(
            firstName = person.firstName,
            lastName = person.lastName,
            middleInitial = person.middleInitial.orEmpty(),
            extensionName = person.extensionName.orEmpty(),
            gender = person.gender,
            contact = person.contact,
            contactPerson = person.contactPerson.orEmpty(),
            contactPersonNumber = person.contactPersonNumber.orEmpty(),
            address = person.address,
            province = person.province,
            cityMunicipality = person.cityMunicipality,
            barangay = person.barangay,
            latitudeText = person.gpsLat?.toString().orEmpty(),
            longitudeText = person.gpsLng?.toString().orEmpty(),
            email = person.email.orEmpty(),
            category = category,
            groupId = groupId,
            congregationId = congregationId,
            accountStatus = person.accountStatus,
            remarks = person.remarks.orEmpty(),
            birthdate = person.birthdate,
            baptismalDate = person.baptismalDate,
        )
    }

    /** [person] with this form's personal fields written onto it (category and group live on the role assignment). */
    fun applyTo(person: Person): Person = person.copy(
        firstName = firstName.trim(),
        lastName = lastName.trim(),
        middleInitial = middleInitial.trim().ifBlank { null },
        extensionName = extensionName.trim().ifBlank { null },
        gender = gender,
        contact = contact.trim(),
        contactPerson = contactPerson.trim().ifBlank { null },
        contactPersonNumber = contactPersonNumber.trim().ifBlank { null },
        address = address.trim(),
        province = province,
        cityMunicipality = cityMunicipality,
        barangay = barangay,
        gpsLat = latitude,
        gpsLng = longitude,
        email = email.trim().ifBlank { null },
        accountStatus = accountStatus,
        remarks = remarks.trim().ifBlank { null },
        birthdate = birthdate,
        baptismalDate = baptismalDate,
    )
}

/**
 * The shared Publisher fields, in this order: First/Last Name (in the user's chosen order), Middle Initial
 * (one letter), Extension Name, Gender, Contact, Contact Person, Contact Person Number, Full Address,
 * Province, Municipality, Barangay, Coordinates, Email, Category, Field Service Group, Status, Remarks.
 *
 * [groupEnabled] false greys the group dropdown (e.g. a Super-Admin who hasn't picked a congregation yet);
 * [allowUnassigned] adds an "Unassigned" choice (Edit only). [onUseCurrentLocation] fills the address
 * levels and coordinates from GPS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublisherFormFields(
    form: PublisherFormState,
    onChange: (PublisherFormState) -> Unit,
    groups: List<Group>,
    /** Every congregation to pick from, and whether the user may change it (a Super-Admin may; everyone else is fixed to their own and sees it read-only). */
    congregations: List<Congregation> = emptyList(),
    congregationEditable: Boolean = false,
    groupEnabled: Boolean = true,
    allowUnassigned: Boolean = false,
    capturingLocation: Boolean = false,
    locationError: String? = null,
    onUseCurrentLocation: () -> Unit,
    /** First-time setup: only the Publisher's own details — no Gender, Category, Group, Status or Remarks, which an administrator controls. */
    profileOnly: Boolean = false,
    /** Slot between the coordinates/email block and the category: the Add form puts the Super-Admin's congregation picker here. */
    beforeAssignment: @Composable () -> Unit = {},
) {
    NameFieldsInOrder(
        first = {
            OutlinedTextField(
                value = form.firstName,
                onValueChange = { onChange(form.copy(firstName = it.uppercase())) },
                label = { Text("First Name") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        last = {
            OutlinedTextField(
                value = form.lastName,
                onValueChange = { onChange(form.copy(lastName = it.uppercase())) },
                label = { Text("Last Name") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        middle = {
            OutlinedTextField(
                value = form.middleInitial,
                // One letter only.
                onValueChange = { onChange(form.copy(middleInitial = it.filter(Char::isLetter).take(1).uppercase())) },
                label = { Text("Middle Initial (one letter only)") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        extension = {
            OutlinedTextField(
                value = form.extensionName,
                onValueChange = { onChange(form.copy(extensionName = it.uppercase())) },
                label = { Text("Extension Name (optional, e.g. JR., III)") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )

    if (!profileOnly) {
        Text("Gender", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Gender.entries.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = form.gender == g, onClick = { onChange(form.copy(gender = g)) })
                    Text(g.name.lowercase().replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(end = 16.dp))
                }
            }
        }
    }
    BirthAndBaptismFields(form, onChange)


    OutlinedTextField(
        value = form.contact,
        onValueChange = { onChange(form.copy(contact = it.uppercase())) },
        label = { Text("Contact") },
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.contactPerson,
        onValueChange = { onChange(form.copy(contactPerson = it.uppercase())) },
        label = { Text("Contact Person (optional)") },
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.contactPersonNumber,
        onValueChange = { onChange(form.copy(contactPersonNumber = it.uppercase())) },
        label = { Text("Contact Person Number (optional)") },
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = form.address,
        onValueChange = { onChange(form.copy(address = it.uppercase())) },
        label = { Text("Full Address") },
        visualTransformation = VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
    PhilippineAddressPicker(
        province = form.province,
        cityMunicipality = form.cityMunicipality,
        barangay = form.barangay,
        onChanged = { province, city, barangay -> onChange(form.copy(province = province, cityMunicipality = city, barangay = barangay)) },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = form.latitudeText,
            onValueChange = { onChange(form.copy(latitudeText = it.filter { c -> c.isDigit() || c == '.' || c == '-' })) },
            label = { Text("Latitude") },
            singleLine = true,
            isError = form.coordinatesProblem != null,
            visualTransformation = VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = form.longitudeText,
            onValueChange = { onChange(form.copy(longitudeText = it.filter { c -> c.isDigit() || c == '.' || c == '-' })) },
            label = { Text("Longitude") },
            singleLine = true,
            isError = form.coordinatesProblem != null,
            visualTransformation = VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    form.coordinatesProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = onUseCurrentLocation, enabled = !capturingLocation, modifier = Modifier.fillMaxWidth()) {
        if (capturingLocation) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            Text("Getting current location…")
        } else {
            Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Use Current Location")
        }
    }
    locationError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    OutlinedTextField(
        value = form.email,
        onValueChange = { onChange(form.copy(email = it)) },
        label = { Text("Email (optional)") },
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )

    if (!profileOnly) {
        beforeAssignment()

        // Congregation: chosen by a Super-Admin (a new publisher starts in the congregation they are working in),
        // fixed and read-only for anyone limited to one congregation. Changing it clears the group, which belongs to
        // the old congregation.
        SimpleChoiceDropdown(
            label = "Congregation",
            selectedText = congregations.firstOrNull { it.id == form.congregationId }?.name.orEmpty(),
            enabled = congregationEditable,
            options = congregations.map { it.name to it.id },
            onSelected = { id -> if (id != form.congregationId) onChange(form.copy(congregationId = id, groupId = null)) },
        )
        SimpleChoiceDropdown(
            label = "Category",
            selectedText = form.category?.displayName.orEmpty(),
            options = PublisherCategory.entries.map { it.displayName to it },
            onSelected = { onChange(form.copy(category = it)) },
        )
        SimpleChoiceDropdown(
            label = "Field Service Group",
            selectedText = groups.firstOrNull { it.id == form.groupId }?.name ?: if (allowUnassigned) "Unassigned" else "",
            placeholder = if (!groupEnabled) "Select a congregation first" else null,
            enabled = groupEnabled,
            options = (if (allowUnassigned) listOf("Unassigned" to null) else emptyList<Pair<String, String?>>()) + groups.map { it.name to it.id },
            onSelected = { onChange(form.copy(groupId = it)) },
        )
        SimpleChoiceDropdown(
            label = "Status",
            selectedText = form.accountStatus.name.lowercase().replaceFirstChar { it.uppercase() },
            options = AccountStatus.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } to it },
            onSelected = { onChange(form.copy(accountStatus = it)) },
        )

        OutlinedTextField(
            value = form.remarks,
            onValueChange = { onChange(form.copy(remarks = it.take(500))) },
            label = { Text("Remarks (optional)") },
            supportingText = { Text("${form.remarks.length}/500") },
            minLines = 3,
            maxLines = 6,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SimpleChoiceDropdown(
    label: String,
    selectedText: String,
    options: List<Pair<String, T>>,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelected(value); expanded = false })
            }
        }
    }
}

/**
 * Optional Birthdate and Baptismal Date (date pickers, may stay blank) with the read-only Age and Baptismal Age worked
 * out from them. The ages are never typed and never stored; an invalid combination shows its message here and also
 * blocks saving (see [PublisherFormState.formProblem]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthAndBaptismFields(form: PublisherFormState, onChange: (PublisherFormState) -> Unit) {
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val age = com.emfitsolutions.gopreach.domain.PersonDates.yearsText(com.emfitsolutions.gopreach.domain.PersonDates.age(form.birthdate))
    val baptismalAge = com.emfitsolutions.gopreach.domain.PersonDates.yearsText(com.emfitsolutions.gopreach.domain.PersonDates.baptismalAge(form.birthdate, form.baptismalDate))
    val birthField: @Composable () -> Unit = {
        DateField("Birthdate (optional)", form.birthdate) { onChange(form.copy(birthdate = it)) }
    }
    val ageField: @Composable () -> Unit = { ReadOnlyTextField("Age", age) }
    val baptismField: @Composable () -> Unit = {
        DateField("Baptismal Date (optional)", form.baptismalDate) { onChange(form.copy(baptismalDate = it)) }
    }
    val baptismalAgeField: @Composable () -> Unit = { ReadOnlyTextField("Baptismal Age", baptismalAge) }
    // Two per row when there is room, stacked on phones.
    if (wide) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { birthField() }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { ageField() }
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { baptismField() }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { baptismalAgeField() }
        }
    } else {
        birthField(); ageField(); baptismField(); baptismalAgeField()
    }
    form.datesProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: Long?, onChange: (Long?) -> Unit) {
    var showPicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    OutlinedTextField(
        value = com.emfitsolutions.gopreach.domain.PersonDates.format(value),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        trailingIcon = {
            Row {
                if (value != null) {
                    androidx.compose.material3.IconButton(onClick = { onChange(null) }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Clear date", modifier = Modifier.size(18.dp))
                    }
                }
                androidx.compose.material3.IconButton(onClick = { showPicker = true }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.CalendarMonth, contentDescription = "Pick date", modifier = Modifier.size(20.dp))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (showPicker) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = value)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onChange(state.selectedDateMillis); showPicker = false }) { Text("OK") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { androidx.compose.material3.DatePicker(state = state) }
    }
}

@Composable
private fun ReadOnlyTextField(label: String, value: String) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
