package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.descriptorUtil.classId

class ModuleIndex(val module: IrModuleFragment) {

    /**
     * Contains all classes declared in [module]
     */
    val classes: Map<ClassId, IrClass>

    init {
        val map = mutableMapOf<ClassId, IrClass>()

        module.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                super.visitClass(declaration)

                val classId = declaration.descriptor.classId
                if (classId != null) {
                    map[classId] = declaration
                }
            }

        })

        classes = map
    }
}