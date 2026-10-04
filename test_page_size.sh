#!/bin/bash

# Test script to verify 16KB page size support in built libraries
# This script checks if the built libraries have the correct ELF alignment

set -e

echo "Checking built libraries for 16KB page size support..."

# Find all built shared libraries
BUILT_LIBS=$(find app/build/intermediates/ndkBuild -name "*.so" 2>/dev/null || true)

if [ -z "$BUILT_LIBS" ]; then
    echo "No built libraries found. Please build the project first with: ./gradlew assembleDebug"
    exit 1
fi

# Check if readelf is available
if ! command -v readelf &> /dev/null; then
    echo "Warning: readelf not found. Cannot verify ELF alignment."
    echo "The libraries should have been built with -Wl,-z,max-page-size=16384 flag."
    echo "Found built libraries:"
    echo "$BUILT_LIBS"
    exit 0
fi

# Check each library
SUCCESS=0
TOTAL=0

for lib in $BUILT_LIBS; do
    echo "Checking $lib..."
    TOTAL=$((TOTAL + 1))
    
    # Check program header alignment for LOAD segments
    ALIGNMENTS=$(readelf -l "$lib" | grep -A1 "LOAD" | grep "0x.*0x.*0x" | awk '{print $NF}' | grep -v "0x0000000000000000")
    
    if [ -n "$ALIGNMENTS" ]; then
        for alignment in $ALIGNMENTS; do
            echo "  LOAD segment alignment: $alignment"
            
            # Convert hex to decimal for comparison
            if [[ "$alignment" =~ ^0x ]]; then
                ALIGNMENT_DEC=$((alignment))
            else
                ALIGNMENT_DEC=$alignment
            fi
            
            # Check if alignment is 16KB (16384) or larger
            if [ "$ALIGNMENT_DEC" -ge 16384 ]; then
                echo "  ✓ 16KB page size compatible (alignment >= 16384 bytes)"
                SUCCESS=$((SUCCESS + 1))
                break
            else
                echo "  ✗ Not 16KB page size compatible (alignment < 16384 bytes)"
            fi
        done
    else
        echo "  Could not determine alignment"
    fi
done

echo ""
echo "Page size support check complete."
echo "Libraries compatible with 16KB page size: $SUCCESS/$TOTAL"

if [ "$SUCCESS" -eq "$TOTAL" ]; then
    echo "✓ All libraries support 16KB page size"
    exit 0
else
    echo "✗ Some libraries do not support 16KB page size"
    exit 1
fi