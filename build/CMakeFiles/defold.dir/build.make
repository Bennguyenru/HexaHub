# CMAKE generated file: DO NOT EDIT!
# Generated by "Unix Makefiles" Generator, CMake Version 3.17

# Delete rule output on recipe failure.
.DELETE_ON_ERROR:


#=============================================================================
# Special targets provided by cmake.

# Disable implicit rules so canonical targets will work.
.SUFFIXES:


# Disable VCS-based implicit rules.
% : %,v


# Disable VCS-based implicit rules.
% : RCS/%


# Disable VCS-based implicit rules.
% : RCS/%,v


# Disable VCS-based implicit rules.
% : SCCS/s.%


# Disable VCS-based implicit rules.
% : s.%


.SUFFIXES: .hpux_make_needs_suffix_list


# Command-line flag to silence nested $(MAKE).
$(VERBOSE)MAKESILENT = -s

# Suppress display of executed commands.
$(VERBOSE).SILENT:


# A target that is always out of date.
cmake_force:

.PHONY : cmake_force

#=============================================================================
# Set environment variables for the build.

# The shell in which to execute make rules.
SHELL = /bin/sh

# The CMake executable.
CMAKE_COMMAND = /usr/local/Cellar/cmake/3.17.3/bin/cmake

# The command to remove a file.
RM = /usr/local/Cellar/cmake/3.17.3/bin/cmake -E rm -f

# Escaping for special characters.
EQUALS = =

# The top-level source directory on which CMake was run.
CMAKE_SOURCE_DIR = /Volumes/StorageSD/Projects/dotGears/defold

# The top-level build directory on which CMake was run.
CMAKE_BINARY_DIR = /Volumes/StorageSD/Projects/dotGears/defold/build

# Include any dependencies generated for this target.
include CMakeFiles/defold.dir/depend.make

# Include the progress variables for this target.
include CMakeFiles/defold.dir/progress.make

# Include the compile flags for this target's objects.
include CMakeFiles/defold.dir/flags.make

CMakeFiles/defold.dir/defold.cpp.o: CMakeFiles/defold.dir/flags.make
CMakeFiles/defold.dir/defold.cpp.o: ../defold.cpp
	@$(CMAKE_COMMAND) -E cmake_echo_color --switch=$(COLOR) --green --progress-dir=/Volumes/StorageSD/Projects/dotGears/defold/build/CMakeFiles --progress-num=$(CMAKE_PROGRESS_1) "Building CXX object CMakeFiles/defold.dir/defold.cpp.o"
	/usr/bin/clang++  $(CXX_DEFINES) $(CXX_INCLUDES) $(CXX_FLAGS) -o CMakeFiles/defold.dir/defold.cpp.o -c /Volumes/StorageSD/Projects/dotGears/defold/defold.cpp

CMakeFiles/defold.dir/defold.cpp.i: cmake_force
	@$(CMAKE_COMMAND) -E cmake_echo_color --switch=$(COLOR) --green "Preprocessing CXX source to CMakeFiles/defold.dir/defold.cpp.i"
	/usr/bin/clang++ $(CXX_DEFINES) $(CXX_INCLUDES) $(CXX_FLAGS) -E /Volumes/StorageSD/Projects/dotGears/defold/defold.cpp > CMakeFiles/defold.dir/defold.cpp.i

CMakeFiles/defold.dir/defold.cpp.s: cmake_force
	@$(CMAKE_COMMAND) -E cmake_echo_color --switch=$(COLOR) --green "Compiling CXX source to assembly CMakeFiles/defold.dir/defold.cpp.s"
	/usr/bin/clang++ $(CXX_DEFINES) $(CXX_INCLUDES) $(CXX_FLAGS) -S /Volumes/StorageSD/Projects/dotGears/defold/defold.cpp -o CMakeFiles/defold.dir/defold.cpp.s

# Object files for target defold
defold_OBJECTS = \
"CMakeFiles/defold.dir/defold.cpp.o"

# External object files for target defold
defold_EXTERNAL_OBJECTS =

libdefold.a: CMakeFiles/defold.dir/defold.cpp.o
libdefold.a: CMakeFiles/defold.dir/build.make
libdefold.a: CMakeFiles/defold.dir/link.txt
	@$(CMAKE_COMMAND) -E cmake_echo_color --switch=$(COLOR) --green --bold --progress-dir=/Volumes/StorageSD/Projects/dotGears/defold/build/CMakeFiles --progress-num=$(CMAKE_PROGRESS_2) "Linking CXX static library libdefold.a"
	$(CMAKE_COMMAND) -P CMakeFiles/defold.dir/cmake_clean_target.cmake
	$(CMAKE_COMMAND) -E cmake_link_script CMakeFiles/defold.dir/link.txt --verbose=$(VERBOSE)

# Rule to build all files generated by this target.
CMakeFiles/defold.dir/build: libdefold.a

.PHONY : CMakeFiles/defold.dir/build

CMakeFiles/defold.dir/clean:
	$(CMAKE_COMMAND) -P CMakeFiles/defold.dir/cmake_clean.cmake
.PHONY : CMakeFiles/defold.dir/clean

CMakeFiles/defold.dir/depend:
	cd /Volumes/StorageSD/Projects/dotGears/defold/build && $(CMAKE_COMMAND) -E cmake_depends "Unix Makefiles" /Volumes/StorageSD/Projects/dotGears/defold /Volumes/StorageSD/Projects/dotGears/defold /Volumes/StorageSD/Projects/dotGears/defold/build /Volumes/StorageSD/Projects/dotGears/defold/build /Volumes/StorageSD/Projects/dotGears/defold/build/CMakeFiles/defold.dir/DependInfo.cmake --color=$(COLOR)
.PHONY : CMakeFiles/defold.dir/depend

