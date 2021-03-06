/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve.ref

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.Substitution
import org.rust.lang.core.types.infer.containsTyOfClass
import org.rust.lang.core.types.infer.foldTyInferWith
import org.rust.lang.core.types.infer.resolve
import org.rust.lang.core.types.infer.substitute
import org.rust.lang.core.types.inference
import org.rust.lang.core.types.regions.ReEarlyBound
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.type
import org.rust.stdext.buildMap

class RsPathReferenceImpl(
    element: RsPath
) : RsReferenceBase<RsPath>(element),
    RsPathReference {

    override val RsPath.referenceAnchor: PsiElement get() = referenceNameElement

    override fun getVariants(): Array<out LookupElement> {
        val lookup = ImplLookup.relativeTo(element)
        return collectCompletionVariants {
            processPathResolveVariants(
                lookup,
                element,
                true,
                filterAssocTypes(
                    element,
                    filterCompletionVariantsByVisibility(
                        filterPathCompletionVariantsByTraitBounds(it, lookup),
                        element.containingMod
                    )
                )
            )
        }
    }

    override fun isReferenceTo(element: PsiElement): Boolean {
        val target = resolve()
        return element.manager.areElementsEquivalent(target, element)
    }

    override fun advancedResolve(): BoundElement<RsElement>? =
        advancedMultiResolve().singleOrNull()

    override fun advancedMultiResolve(): List<BoundElement<RsElement>> =
        (element.parent as? RsPathExpr)?.let { it.inference?.getResolvedPaths(it)?.map { BoundElement(it) } }
            ?: advancedCachedMultiResolve()

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        return advancedMultiResolve().toTypedArray()
    }

    override fun multiResolve(): List<RsNamedElement> =
        advancedMultiResolve().mapNotNull { it.element as? RsNamedElement }

    private fun advancedCachedMultiResolve(): List<BoundElement<RsElement>> {
        return RsResolveCache.getInstance(element.project)
            .resolveWithCaching(element, ResolveCacheDependency.RUST_STRUCTURE, Resolver)
            .orEmpty()
            // We can store a fresh `TyInfer.TyVar` to the cache for `_` path parameter (like `Vec<_>`), but
            // TyVar is mutable type, so we must copy it after retrieving from the cache
            .map { it.foldTyInferWith { if (it is TyInfer.TyVar) TyInfer.TyVar(it.origin) else it } }
    }

    private object Resolver : (RsPath) -> List<BoundElement<RsElement>> {
        override fun invoke(element: RsPath): List<BoundElement<RsElement>> {
            return resolvePath(element)
        }
    }
}

private fun filterAssocTypes(
    path: RsPath,
    processor: RsResolveProcessor
): RsResolveProcessor {
    val qualifier = path.path
    val allAssocItemsAllowed =
        qualifier == null || qualifier.hasCself || qualifier.reference.resolve() is RsTypeParameter
    return if (allAssocItemsAllowed) processor else fun(it: ScopeEntry): Boolean {
        if (it is AssocItemScopeEntry && (it.element is RsTypeAlias)) return false
        return processor(it)
    }
}

private fun filterPathCompletionVariantsByTraitBounds(
    processor: RsResolveProcessor,
    lookup: ImplLookup
): RsResolveProcessor {
    val cache = mutableMapOf<RsImplItem, Boolean>()
    return fun(it: ScopeEntry): Boolean {
        if (it !is AssocItemScopeEntry) return processor(it)
        if (it.source !is TraitImplSource.ExplicitImpl) return processor(it)

        val receiver = it.subst[TyTypeParameter.self()] ?: return processor(it)
        // Don't filter partially unknown types
        if (receiver.containsTyOfClass(TyUnknown::class.java)) return processor(it)
        // Filter members by trait bounds (try to select all obligations for each impl)
        // We're caching evaluation results here because we can often complete members
        // in the same impl and always have the same receiver type
        val canEvaluate = cache.getOrPut(it.source.value) {
            lookup.ctx.canEvaluateBounds(it.source.value, receiver)
        }
        if (canEvaluate) return processor(it)

        return false
    }
}

fun resolvePath(path: RsPath, lookup: ImplLookup = ImplLookup.relativeTo(path)): List<BoundElement<RsElement>> {
    val result = collectPathResolveVariants(path.referenceName) {
        processPathResolveVariants(lookup, path, false, it)
    }

    val typeArguments: List<Ty>? = run {
        val inAngles = path.typeArgumentList
        val fnSugar = path.valueParameterList
        when {
            inAngles != null -> inAngles.typeReferenceList.map { it.type }
            fnSugar != null -> listOf(
                TyTuple(fnSugar.valueParameterList.map { it.typeReference?.type ?: TyUnknown })
            )
            else -> null
        }
    }

    val regionArguments: List<Region>? = path.typeArgumentList?.lifetimeList?.map { it.resolve() }

    val outputArg = path.retType?.typeReference?.type

    return result.map { boundElement ->
        val (element, subst) = boundElement.downcast<RsGenericDeclaration>() ?: return@map boundElement

        val assocTypes = run {
            if (element is RsTraitItem) {
                buildMap {
                    // Iterator<Item=T>
                    path.typeArgumentList?.assocTypeBindingList?.forEach { binding ->
                        // We can't just use `binding.reference.resolve()` here because
                        // resolving of an assoc type depends on a parent path resolve,
                        // so we coming back here and entering the infinite recursion
                        resolveAssocTypeBinding(element, binding)?.let { assoc ->
                            binding.typeReference?.type?.let { put(assoc, it) }
                        }

                    }

                    // Fn() -> T
                    val outputParam = lookup.fnOnceOutput
                    if (outputArg != null && outputParam != null) {
                        put(outputParam, outputArg)
                    }
                }
            } else {
                emptyMap<RsTypeAlias, Ty>()
            }
        }

        val typeParameters = element.typeParameters.map { TyTypeParameter.named(it) }
        val regionParameters = element.lifetimeParameters.map { ReEarlyBound(it) }
        val typeSubst = typeParameters.zip(typeArguments ?: typeParameters).toMap()
        val regionSubst = regionParameters.zip(regionArguments ?: regionParameters).toMap()
        val newSubst = Substitution(typeSubst, regionSubst)
        BoundElement(element, subst + newSubst, assocTypes)
    }
}

private fun resolveAssocTypeBinding(trait: RsTraitItem, binding: RsAssocTypeBinding): RsTypeAlias? =
    collectResolveVariants(binding.referenceName) { processAssocTypeVariants(trait, it) }
        .singleOrNull() as? RsTypeAlias?

/** Resolves a reference through type aliases */
fun RsPathReference.deepResolve(): RsElement? =
    advancedDeepResolve()?.element

/** Resolves a reference through type aliases */
fun RsPathReference.advancedDeepResolve(): BoundElement<RsElement>? {
    val boundElement = advancedResolve()?.let { resolved ->
        // Resolve potential `Self` inside `impl`
        if (resolved.element is RsImplItem && element.hasCself) {
            (resolved.element.typeReference?.typeElement as? RsBaseType)?.path?.reference?.advancedResolve()
        } else {
            resolved
        }
    }

    // Resolve potential type aliases
    return if (boundElement != null && boundElement.element is RsTypeAlias) {
        resolveThroughTypeAliases(boundElement)
    } else {
        boundElement
    }
}

private fun resolveThroughTypeAliases(boundElement: BoundElement<RsElement>): BoundElement<RsElement>? {
    var base: BoundElement<RsElement> = boundElement
    val visited = mutableSetOf(boundElement.element)
    while (base.element is RsTypeAlias) {
        val resolved = ((base.element as RsTypeAlias).typeReference?.typeElement as? RsBaseType)
            ?.path?.reference?.advancedResolve()
            ?: break
        if (!visited.add(resolved.element)) return null
        base = resolved.substitute(base.subst)
    }
    return base
}
