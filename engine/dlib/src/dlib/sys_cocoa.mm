#include <assert.h>
#include <stdint.h>
#include <sys/utsname.h>
#import <Foundation/NSFileManager.h>
#include "log.h"
#include "sys.h"
#include "sys_private.h"
#include "dstrings.h"

#if defined(__arm__) || defined(__arm64__)
#import <UIKit/UIApplication.h>
#import <UIKit/UIKit.h>
#import <AdSupport/AdSupport.h>
#else
#import <AppKit/NSWorkspace.h>
#import <Foundation/NSLocale.h>
#endif

namespace dmSys
{
#if defined(__arm__) || defined(__arm64__)
    // Only on iOS for now. No autorelease pool etc setup on OSX in dlib
    Result GetApplicationSupportPath(const char* application_name, char* path, uint32_t path_len)
    {
    	assert(path_len > 0);
        NSFileManager* shared_fm = [NSFileManager defaultManager];
        NSArray* urls = [shared_fm URLsForDirectory:NSApplicationSupportDirectory
                                          inDomains:NSUserDomainMask];


        if ([urls count] > 0)
        {
            NSURL* app_support_dir = [urls objectAtIndex:0];
            dmStrlCpy(path, [[app_support_dir path] UTF8String], path_len);

            path[path_len-1] = '\0';

            if (dmStrlCat(path, "/", path_len) >= path_len)
                return RESULT_INVAL;
            if (dmStrlCat(path, application_name, path_len) >= path_len)
                return RESULT_INVAL;

            NSString* ns_path = [NSString stringWithUTF8String: path];
            Result r;
            if ([shared_fm createDirectoryAtPath: ns_path withIntermediateDirectories: TRUE attributes: nil error: nil] == YES)
            {
                r = RESULT_OK;
            }
            else
            {
                r = RESULT_UNKNOWN;
            }
            return r;
        }
        else
        {
        	dmLogError("Unable to locate NSApplicationSupportDirectory");
            return RESULT_UNKNOWN;
        }
    }

    Result GetLogPath(char* path, uint32_t path_len)
    {
        NSString* p = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) lastObject];
        const char* s = [p UTF8String];

        if (dmStrlCpy(path, s, path_len) >= path_len)
            return RESULT_INVAL;

        return RESULT_OK;
    }

    Result OpenURL(const char* url)
    {
        NSString* ns_url = [NSString stringWithUTF8String: url];
        BOOL ret = [[UIApplication sharedApplication] openURL:[NSURL URLWithString: ns_url]];
        if (ret == YES)
        {
            return RESULT_OK;
        }
        else
        {
            return RESULT_UNKNOWN;
        }
    }

    void GetSystemInfo(struct SystemInfo* info)
    {
        memset(info, 0, sizeof(*info));

        UIDevice* d = [UIDevice currentDevice];
        struct utsname uts;
        uname(&uts);

        dmStrlCpy(info->m_Manufacturer, "Apple", sizeof(info->m_Manufacturer));
        dmStrlCpy(info->m_DeviceModel, uts.machine, sizeof(info->m_DeviceModel));
        dmStrlCpy(info->m_SystemName, [d.systemName UTF8String], sizeof(info->m_SystemName));
        dmStrlCpy(info->m_SystemVersion, [d.systemVersion UTF8String], sizeof(info->m_SystemVersion));

        NSLocale* locale = [NSLocale currentLocale];
        const char* lang = [locale.localeIdentifier UTF8String];
        FillLanguageTerritory(lang, info);
        FillTimeZone(info);
        dmStrlCpy(info->m_DeviceIdentifier, [[d.identifierForVendor UUIDString] UTF8String], sizeof(info->m_DeviceIdentifier));

        ASIdentifierManager* asim = [ASIdentifierManager sharedManager];
        dmStrlCpy(info->m_AdIdentifier, [[asim.advertisingIdentifier UUIDString] UTF8String], sizeof(info->m_AdIdentifier));
        info->m_AdTrackingEnabled = (bool) asim.advertisingTrackingEnabled;

        NSString *device_language = [[NSLocale preferredLanguages]objectAtIndex:0];
        dmStrlCpy(info->m_DeviceLanguage, [device_language UTF8String], sizeof(info->m_DeviceLanguage));
    }

#else

    void GetSystemInfo(SystemInfo* info)
    {
        memset(info, 0, sizeof(*info));
        struct utsname uts;
        uname(&uts);

        dmStrlCpy(info->m_SystemName, uts.sysname, sizeof(info->m_SystemName));
        dmStrlCpy(info->m_SystemVersion, uts.release, sizeof(info->m_SystemVersion));
        info->m_DeviceModel[0] = '\0';

        const char* default_lang = "en_US";
        const char* lang = default_lang;

        NSLocale* locale = [NSLocale currentLocale];

        if (0x0 != locale) {
            NSString* preferredLang = [locale localeIdentifier];
            lang = [preferredLang UTF8String];
        }

        if (!lang) {
            dmLogWarning("localeIdentifier not available.");
        }

        FillLanguageTerritory(lang, info);
        FillTimeZone(info);
    }

    Result OpenURL(const char* url)
    {
        NSString* ns_url = [NSString stringWithUTF8String: url];
        BOOL ret = [[NSWorkspace sharedWorkspace] openURL:[NSURL URLWithString: ns_url]];
        if (ret == YES)
        {
            return RESULT_OK;
        }
        else
        {
            return RESULT_UNKNOWN;
        }
    }

#endif
}

