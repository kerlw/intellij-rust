/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.editor

import com.intellij.editor.TodoItemsTestCase
import com.intellij.lexer.LayeredLexer
import org.intellij.lang.annotations.Language
import org.rust.lang.RsFileType

class RsTodoTest : TodoItemsTestCase() {

    override fun supportsCStyleMultiLineComments(): Boolean = true
    override fun supportsCStyleSingleLineComments(): Boolean = true
    override fun getFileExtension(): String = RsFileType.defaultExtension

    fun `test single todo`() = doTest("""
        // [TODO first line]
        // second line
        fn main() {}
    """)

    fun `test multiline todo in block comment`() = doTest("""
        /* [TODO first line]
            [second line]*/
        fn main() {}
    """)

    fun `test multiline todo in outer eol doc comment`() = doTest("""
        /// [TODO first line]
        ///  [second line]
        fn main() {}
    """)

    fun `test multiline todo in outer block doc comment`() = doTest("""
        /** [TODO first line]
             [second line]*/
        fn main() {}
    """)

    fun `test multiline todo in inner eol doc comment`() = doTest("""
        mod foo {
            //! [TODO first line]
            //!  [second line]
        }
    """)

    fun `test multiline todo in inner block doc comment`() = doTest("""
        mod foo {
            /*! [TODO first line]
                 [second line]*/
        }
    """)

    // TODO: find out why doc tests fail
    //  when additional layers of `RsHighlightingLexer` are enabled
    //  although everything is fine in real IDE
    private fun doTest(@Language("Rust") text: String) {
        try {
            LayeredLexer.ourDisableLayersFlag.set(true)
            testTodos(text)
        } finally {
            LayeredLexer.ourDisableLayersFlag.set(null)
        }
    }
}
