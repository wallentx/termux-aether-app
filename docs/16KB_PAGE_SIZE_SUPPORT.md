# 16KB Page Size Support

## Overview

This document describes the 16KB page size support added to the Termux app. Android is transitioning from 4KB to 16KB page sizes for improved performance on newer devices and emulators.

## What was changed

### Native Library Build Configuration

The following changes were made to ensure native libraries are compatible with 16KB page sizes:

1. **app/build.gradle**: Added linker flags for 16KB page alignment:
   ```gradle
   externalNativeBuild {
       ndkBuild {
           cFlags "-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections"
           ldFlags "-Wl,-z,max-page-size=16384"
       }
   }
   ```

2. **Android.mk files**: Added `LOCAL_LDFLAGS` to all native modules:
   ```makefile
   LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
   ```

   This was applied to:
   - `app/src/main/cpp/Android.mk` (libtermux-bootstrap)
   - `termux-shared/src/main/cpp/Android.mk` (local-socket)
   - `terminal-emulator/src/main/jni/Android.mk` (libtermux)

## Technical Details

### What is 16KB Page Size?

- Traditional Android systems use 4KB memory pages
- Newer Android systems and emulators support 16KB pages for better performance
- ELF binaries must be aligned to page boundaries to load correctly

### The Solution

The `-Wl,-z,max-page-size=16384` linker flag ensures that:

1. ELF segments are aligned to 16KB (16384 bytes) boundaries
2. Libraries remain compatible with both 4KB and 16KB page systems
3. The app can run on newer Android systems with 16KB pages

## Testing

### Manual Testing

To verify the changes work correctly:

1. Build the app with the new configuration
2. Use the provided test script to check ELF alignment:
   ```bash
   ./test_page_size.sh path/to/library.so
   ```

### Automated Testing

A unit test (`PageSizeTest.java`) was added to verify the build configuration includes 16KB page size support.

### Testing on 16KB Page Systems

To test on a 16KB page system:

1. Use the Google APIs Experimental 16KB Page Size ARM64 v8a System Image
2. Install and run Termux
3. Verify that all native libraries load without issues

## References

- [Android 16KB Page Size Guide](https://developer.android.com/guide/practices/page-sizes)
- [16KB Page Size Emulator](https://developer.android.com/guide/practices/page-sizes#16kb-emulator)
- [ELF Alignment Testing](https://developer.android.com/guide/practices/page-sizes#test)

## Compatibility

This change maintains backward compatibility:
- Apps built with 16KB alignment work on 4KB page systems
- The change only affects the build process, not runtime behavior
- No API changes or user-facing changes are required