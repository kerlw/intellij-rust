package org.rust.lang.core.psi.util

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.RustPat
import org.rust.lang.core.psi.RustPatIdent

//
// Extension points
//

fun PsiElement?.match(s: String?): Boolean {
    return this != null
            && s != null
            && text.equals(s);
}

val PsiElement.parentRelativeRange: TextRange?
    get() = this.parent?.let {
        TextRange(startOffsetInParent, startOffsetInParent + textLength)
    }

val RustPat.boundIdentifiers: List<RustPatIdent>
    get() {
        val result = arrayListOf<RustPatIdent>()
        accept(object : RecursiveRustVisitor() {
            override fun visitPatIdent(o: RustPatIdent) {
                result.add(o)
            }
        })
        return result
    }
