package org.javacs.kt.index

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi2ir.intermediate.extensionReceiverType
import org.javacs.kt.LOG
import org.javacs.kt.progress.Progress
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.insert

private object Symbols : Table() {
    val fqName = varchar("fqname", length = 255) references FqNames.fqName
    val kind = integer("kind")
    val visibility = integer("visibility")
    val extensionReceiverType = varchar("extensionreceivertype", length = 255).nullable()

    override val primaryKey = PrimaryKey(fqName)
}

private object FqNames : Table() {
    val fqName = varchar("fqname", length = 255)
    val shortName = varchar("shortname", length = 80)

    override val primaryKey = PrimaryKey(fqName)
}

/**
 * A global view of all available symbols across all packages.
 */
class SymbolIndex {
    private val db = Database.connect("jdbc:h2:mem:symbolindex;DB_CLOSE_DELAY=-1", "org.h2.Driver")

    var progressFactory: Progress.Factory = Progress.Factory.None

    init {
        transaction(db) {
            SchemaUtils.create(Symbols, FqNames)
        }
    }

    /** Rebuilds the entire index. May take a while. */
    fun refresh(module: ModuleDescriptor) {
        val started = System.currentTimeMillis()
        LOG.info("Updating symbol index...")

        progressFactory.create("Indexing").thenApply { progress ->
            try {
                val descriptors = allDescriptors(module)

                // TODO: Incremental updates
                transaction(db) {
                    Symbols.deleteAll()

                    for (descriptor in descriptors) {
                        val fqn = descriptor.fqNameSafe
                        val extensionReceiverFqn = descriptor.accept(ExtractSymbolExtensionReceiverType, Unit)

                        FqNames.replace {
                            it[fqName] = fqn.toString()
                            it[shortName] = fqn.shortName().toString()
                        }

                        extensionReceiverFqn?.let { rFqn ->
                            FqNames.replace {
                                it[fqName] = rFqn.toString()
                                it[shortName] = rFqn.shortName().toString()
                            }
                        }

                        Symbols.replace {
                            it[fqName] = fqn.toString()
                            it[kind] = descriptor.accept(ExtractSymbolKind, Unit).rawValue
                            it[visibility] = descriptor.accept(ExtractSymbolVisibility, Unit).rawValue
                            it[extensionReceiverType] = extensionReceiverFqn?.toString()
                        }
                    }

                    val finished = System.currentTimeMillis()
                    val count = Symbols.slice(Symbols.fqName.count()).selectAll().first()[Symbols.fqName.count()]
                    LOG.info("Updated symbol index in ${finished - started} ms! (${count} symbol(s))")
                }
            } catch (e: Exception) {
                LOG.error("Error while updating symbol index")
                LOG.printStackTrace(e)
            }

            progress.close()
        }
    }

    fun query(prefix: String, receiverType: FqName? = null, limit: Int = 20): List<Symbol> = transaction(db) {
        // TODO: Extension completion currently only works if the receiver matches exactly,
        //       ideally this should work with subtypes as well
        (Symbols innerJoin FqNames)
            .select { FqNames.shortName.like("$prefix%") and (Symbols.extensionReceiverType eq receiverType?.toString()) }
            .limit(limit)
            .map { Symbol(
                fqName = FqName(it[Symbols.fqName]),
                kind = Symbol.Kind.fromRaw(it[Symbols.kind]),
                visibility = Symbol.Visibility.fromRaw(it[Symbols.visibility]),
                extensionReceiverType = it[Symbols.extensionReceiverType]?.let(::FqName)
            ) }
    }

    private fun allDescriptors(module: ModuleDescriptor): Collection<DeclarationDescriptor> = allPackages(module)
        .map(module::getPackage)
        .flatMap {
            try {
                it.memberScope.getContributedDescriptors(DescriptorKindFilter.ALL, MemberScope.ALL_NAME_FILTER)
            } catch (e: IllegalStateException) {
                LOG.warn("Could not query descriptors in package $it")
                emptyList()
            }
        }

    private fun allPackages(module: ModuleDescriptor, pkgName: FqName = FqName.ROOT): Collection<FqName> = module
        .getSubPackagesOf(pkgName) { it.toString() != "META-INF" }
        .flatMap { setOf(it) + allPackages(module, it) }
}
