package io.andreygrigoriev.yfin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.module.ModuleDescriptor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

// a native image has no module graph to inspect
@DisabledInNativeImage
class BuildSmokeTest {

    private static ModuleDescriptor descriptor() {
        Module module = BuildSmokeTest.class.getModule();
        assertThat(module.isNamed()).as("tests run on the module path").isTrue();
        return module.getDescriptor();
    }

    @Test
    void moduleName() {
        assertThat(descriptor().name()).isEqualTo("io.andreygrigoriev.yfin");
    }

    @Test
    void exportsOnlyPublicPackages() {
        var exports = descriptor().exports();

        assertThat(exports.stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "io.andreygrigoriev.yfin",
                        "io.andreygrigoriev.yfin.model",
                        "io.andreygrigoriev.yfin.request",
                        "io.andreygrigoriev.yfin.spi",
                        "io.andreygrigoriev.yfin.exception");
        assertThat(exports).allSatisfy(e -> {
            assertThat(e.isQualified()).as("unqualified export %s", e.source()).isFalse();
            assertThat(e.source()).doesNotContain(".internal");
        });
    }

    @Test
    void opensNothing() {
        assertThat(descriptor().isOpen()).isFalse();
        assertThat(descriptor().opens()).isEmpty();
    }

    @Test
    void jspecifyIsCompileOnly() {
        assertThat(descriptor().requires())
                .filteredOn(r -> r.name().equals("org.jspecify"))
                .singleElement()
                .satisfies(r -> assertThat(r.modifiers()).contains(ModuleDescriptor.Requires.Modifier.STATIC));
    }
}
