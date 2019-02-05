#include "macos_events.h" // interface
#include <stdlib.h> // malloc and free
#include <string.h> // strlen and memcpy
#import <Cocoa/Cocoa.h> // NS stuff

char** files;

@interface AppDelegate : NSObject <NSApplicationDelegate>
@end

@implementation AppDelegate

- (void)application:(NSApplication *)sender openFiles:(NSArray<NSString *> *)filenames {
  size_t names_count = [filenames count];
  files = (char**)malloc(names_count + 1);
  NSAutoreleasePool* pool = [[NSAutoreleasePool alloc] init];
  NSFileManager* fm = [NSFileManager defaultManager];
  NSString* cwd = [fm currentDirectoryPath];
  int _fileIndex = 0;
  int* fileIndex = &_fileIndex;
  [filenames enumerateObjectsUsingBlock:^(NSString* str, NSUInteger idx, BOOL *stop) {
      if(![str isAbsolutePath]) {
        str = [str stringByExpandingTildeInPath];
        if(![str isAbsolutePath]) {
          NSArray* pathComponents = [NSArray arrayWithObjects: cwd, str, nil];
          str = [NSString pathWithComponents: pathComponents];
        }
      }
      if([fm fileExistsAtPath: str]) {
        const char *filename = [str cStringUsingEncoding:NSUTF8StringEncoding];
        size_t len = strlen(filename) + 1;
        files[*fileIndex] = (char*)malloc(len);
        memcpy(files[*fileIndex], filename, len);
        ++(*fileIndex);
      }
      files[*fileIndex] = NULL;
    }];
  [pool drain];
}

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification {
  // Tell application to stop and post an empty event to make it actually happen.
  [[NSApplication sharedApplication] stop:nil];
  NSAutoreleasePool* pool = [[NSAutoreleasePool alloc] init];
  NSEvent* event = [NSEvent otherEventWithType:NSEventTypeApplicationDefined
                                      location:NSMakePoint(0, 0)
                                 modifierFlags:0
                                     timestamp:0
                                  windowNumber:0
                                       context:nil
                                       subtype:0
                                         data1:0
                                         data2:0];
  [NSApp postEvent:event atStart:YES];
  [pool drain];
}

- (void)dealloc {
  [super dealloc];
}

@end

char** ReceiveFileOpenEvent() {
  files = NULL;
  [NSApplication sharedApplication];
  AppDelegate* delegate = [[AppDelegate alloc] init];

  [NSApp setDelegate:delegate];
  [NSApp setActivationPolicy: NSApplicationActivationPolicyProhibited];
  [NSApp run];

  [delegate dealloc];
  return files;
}

void FreeFileList(char** fileList) {
  if(!fileList) {
    return;
  }
  char** p = fileList;
  while(*p) {
    free(*p);
    p++;
  }
  free(fileList);
}

