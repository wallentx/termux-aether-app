# Termux App - Copilot Instructions

This is an Android terminal emulator application with a Linux environment. The repository consists of multiple modules including the main Termux app, terminal emulator, terminal view, and shared libraries.

## Code Standards

### Required Before Each Commit
- Follow the [Conventional Commits](https://www.conventionalcommits.org) specification for all commit messages
- **This project uses a custom variant** with capitalized types for automatic changelog generation
- Commit types must be: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, or `Security`
- First letter for `type` and `description` must be capital, description in present tense
- Format: `<type>[optional scope]: <description>`
- Example: `Fixed: Terminal crash on Android 12`, `Added(terminal): Color scheme support`, `Changed!: Update minimum SDK to API 24`

### Code Formatting
- Use spaces for indentation (4 spaces for Java/Gradle, 2 spaces for YAML)
- Follow `.editorconfig` settings: Unix line endings (LF), UTF-8 charset, final newline
- No specific linter is configured, but code should follow standard Java conventions

### Development Flow
- Build: `./gradlew build` or `./gradlew assembleDebug`
- Test: `./gradlew test` or `./gradlew testDebugUnitTest`
- Run all checks: `./gradlew check`
- Lint: `./gradlew lint` or `./gradlew lintDebug`
- Clean: `./gradlew clean`

## Repository Structure

### Key Directories
- `app/`: Main Termux application - contains the app UI, activities, fragments, and services
- `terminal-emulator/`: Low-level terminal emulation logic
- `terminal-view/`: Android view components for the terminal
- `termux-shared/`: Shared constants, utilities, and libraries used across Termux and plugins
  - Contains `TermuxConstants` which defines all shared constants
  - Must be used instead of hardcoded paths and values
- `docs/`: Documentation files
- `.github/`: GitHub configuration, workflows, issue templates

### Source Structure
- Java source: `app/src/main/java/com/termux/`
- Tests: `app/src/test/java/com/termux/`
- Resources: `app/src/main/res/`

## Key Guidelines

### 1. Use termux-shared Library
- **ALWAYS** use constants and utilities from `termux-shared` library
- **NEVER** use hardcoded paths or values
- Define new shared constants in `termux-shared/src/main/java/com/termux/shared/termux/` package
- Termux-specific classes go under `com.termux.shared.termux` package
- General utility classes go outside that package
- Update `termux-shared/LICENSE.md` when adding external code

### 2. Package Naming and Structure
- Main package: `com.termux`
- Follow existing package structure within each module
- Plugin-related code: `com.termux.shared.termux.plugins`
- Shell-related code: `com.termux.shared.shell`
- File utilities: `com.termux.shared.file`

### 3. Android Best Practices
- Min SDK: API level 21 (Android 5.0)
- Target SDK: Check `gradle.properties` for current target
- Use AndroidX libraries, not legacy support libraries
- Follow Android lifecycle best practices for Activities and Services

### 4. Version Management
- Version format: `major.minor.patch(-prerelease)(+buildmetadata)`
- Follow [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html)
- Always include patch number in versions and tags (e.g., `v0.118.0`, not `v0.118`)
- Update `versionName` in `app/build.gradle` when releasing

### 5. Build Variants
- Three package variants: `pacman-android-7` (default), `apt-android-7`, and `apt-android-5`
- Set via `TERMUX_PACKAGE_VARIANT` environment variable
- Bootstrap packages must match the variant
- Debug builds are signed with test key (for development only)

### 6. Testing
- Write unit tests for new functionality
- Place tests in `app/src/test/java/` directory
- Use JUnit for testing
- Run tests before submitting pull requests

### 7. Documentation
- Update documentation when making significant changes
- Keep README.md current with installation and usage instructions
- Document public APIs with Javadoc
- Update relevant wiki pages when changing core functionality

### 8. Security Considerations
- Never commit secrets or credentials
- Be cautious with file permissions (note: past world-readable vulnerability)
- Follow Android security best practices for intents and content providers
- Be aware of Android 12+ phantom process limitations

### 9. Plugin Compatibility
- Maintain compatibility with Termux plugins (API, Boot, Float, Styling, Tasker, Widget)
- All apps must be signed with the same key to work together
- Use `sharedUserId` `com.termux` for all apps
- Changes to shared functionality must consider plugin compatibility

### 10. Code Review Standards
- Follow existing code patterns and architecture
- Maintain consistency with surrounding code
- Consider backward compatibility
- Test on multiple Android versions when possible
- Be mindful of performance on older devices

## Common Tasks

### Building APKs
```bash
# Debug build (universal APK)
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Build specific variant
TERMUX_PACKAGE_VARIANT=apt-android-5 ./gradlew assembleDebug
```

### Running Tests
```bash
# Run all unit tests
./gradlew test

# Run specific test suite
./gradlew testDebugUnitTest

# Run with coverage
./gradlew testDebugUnitTestCoverage
```

### Code Quality
```bash
# Run lint checks
./gradlew lint

# Fix auto-fixable lint issues
./gradlew lintFix

# Generate lint report
./gradlew lintDebug

# Run all verification tasks
./gradlew check
```

## Important Notes

### For Forking
- Check `TermuxConstants` javadocs for package name changes
- Recompile bootstrap with new package name
- Update hardcoded values in plugins
- Update manifest placeholders in `app/build.gradle`

### Dependencies
- Use stable, well-maintained libraries
- Check compatibility with min SDK version
- Update dependencies cautiously to avoid breaking changes
- Prefer AndroidX over legacy support libraries

### Native Code
- NDK version specified in `gradle.properties` or `JITPACK_NDK_VERSION` env var
- Native code in `app/src/main/jni/`
- Build flags: `-std=c11 -Wall -Wextra -Werror -Os`

## Resources

### Documentation
- [Termux Wiki](https://wiki.termux.com/wiki/)
- [Termux App Wiki](https://github.com/termux/termux-app/wiki)
- [FAQ](https://wiki.termux.com/wiki/FAQ)

### Community
- [Reddit](https://reddit.com/r/termux)
- [Matrix/Gitter](https://matrix.to/#/#termux_termux:gitter.im)
- [GitHub Issues](https://github.com/termux/termux-app/issues)

### Reference
- Android version support: Android 7+ (full support), Android 5-6 (app only, no package updates)
- Package management: `apt` and `pkg` commands
- Terminal emulation based on Android Terminal Emulator project
