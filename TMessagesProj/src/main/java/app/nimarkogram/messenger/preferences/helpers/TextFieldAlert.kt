 
package app.nimarkogram.messenger.preferences.helpers

object TextFieldAlert {

    fun removeNonNumericChars(input: String, allowMinus: Boolean): String {
        return if (allowMinus) {
            input.replace(Regex("[^0-9-]"), "")
        } else {
            input.replace(Regex("[^0-9]"), "")
        }
    }

}
