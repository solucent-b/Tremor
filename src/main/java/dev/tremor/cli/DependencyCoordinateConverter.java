package dev.tremor.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/** Lets picocli turn {@code --dependency} text into a {@link DependencyCoordinate}. */
public final class DependencyCoordinateConverter implements ITypeConverter<DependencyCoordinate> {

    @Override
    public DependencyCoordinate convert(String value) {
        try {
            return DependencyCoordinate.parse(value);
        } catch (IllegalArgumentException e) {
            throw new TypeConversionException(e.getMessage());
        }
    }
}
