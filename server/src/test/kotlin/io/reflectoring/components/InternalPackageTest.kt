package io.reflectoring.components

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException


class InternalPackageTest {

    private val BASE_PACKAGE = "io.reflectoring.components"
    private val analyzedClasses = ClassFileImporter().importPackages(BASE_PACKAGE)

    @Test
    @Throws(IOException::class)
    fun internalPackagesAreNotAccessedFromOutside() {

        // so that the test will break when the base package is re-named
        assertPackageExists(BASE_PACKAGE)
        val internalPackages = internalPackages(BASE_PACKAGE)
        // so that the test will break when the scan silently finds nothing
        assertThat(internalPackages).isNotEmpty()
        for (internalPackage in internalPackages) {
            assertPackageExists(internalPackage)
            assertPackageIsNotAccessedFromOutside(internalPackage)
        }
    }

    /**
     * Finds all packages named "internal".
     */
    private fun internalPackages(basePackage: String): List<String> {
        return analyzedClasses.map {
            it.packageName
        }.filter {
            it.startsWith(basePackage) && it.endsWith(".internal")
        }.distinct()
    }

    private fun assertPackageIsNotAccessedFromOutside(internalPackage: String) {
        noClasses()
            .that()
            .resideOutsideOfPackage(packageMatcher(internalPackage))
            .should()
            .dependOnClassesThat()
            .resideInAPackage(packageMatcher(internalPackage))
            .check(analyzedClasses)
    }

    private fun assertPackageExists(packageName: String?) {
        assertThat(analyzedClasses.containPackage(packageName))
            .`as`("package %s exists", packageName)
            .isTrue()
    }

    private fun packageMatcher(fullyQualifiedPackage: String): String? {
        return "$fullyQualifiedPackage.."
    }

}