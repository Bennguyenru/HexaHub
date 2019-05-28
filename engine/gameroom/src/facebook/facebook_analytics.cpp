#include "facebook_analytics.h"

#include <string.h>
#include <cstring>
#include <cstdlib>

#include <dlib/log.h>
#include "facebook_private.h"

namespace
{
    const char* EVENT_TABLE[dmFacebookGR::Analytics::MAX_NUM_EVENTS] = {
        "fb_mobile_level_achieved",
        "fb_mobile_activate_app",
        "fb_mobile_add_payment_info",
        "fb_mobile_add_to_cart",
        "fb_mobile_add_to_wishlist",
        "fb_mobile_complete_registration",
        "fb_mobile_tutorial_completion",
        "fb_mobile_deactivate_app",
        "fb_mobile_initiated_checkout",
        "fb_mobile_purchase",
        "fb_mobile_rate",
        "fb_mobile_search",
        "fb_mobile_app_interruptions",
        "fb_mobile_spent_credits",
        "fb_mobile_time_between_sessions",
        "fb_mobile_achievement_unlocked",
        "fb_mobile_content_view"
    };

    const char* PARAMETER_TABLE[dmFacebookGR::Analytics::MAX_NUM_PARAMS] = {
        "fb_content_id",
        "fb_content_type",
        "fb_currency",
        "fb_description",
        "fb_level",
        "fb_max_rating_value",
        "fb_num_items",
        "fb_payment_info_available",
        "fb_registration_method",
        "fb_search_string",
        "fb_mobile_launch_source",
        "fb_success"
    };

    const char* LookupEvent(unsigned int index)
    {
        if (index < dmFacebookGR::Analytics::MAX_NUM_EVENTS) {
            return ::EVENT_TABLE[index];
        }

        return 0;
    }

    const char* LookupParameter(unsigned int index)
    {
        if (index < dmFacebookGR::Analytics::MAX_NUM_PARAMS)
        {
            return ::PARAMETER_TABLE[index];
        }

        return 0;
    }
};

const char* dmFacebookGR::Analytics::GetEvent(lua_State* L, int index)
{
    const char* event = 0;
    if (lua_isnil(L, index))
    {
        luaL_argerror(L, index, "Facebook Analytics event cannot be nil");
    }
    else if (lua_isnumber(L, index))
    {
        unsigned int event_number = (unsigned int) luaL_checknumber(L, index);
        event = ::LookupEvent(event_number);
        if (event == 0)
        {
            luaL_argerror(L, index, "Facebook Analytics event does not exist");
        }
    }
    else if (lua_isstring(L, index))
    {
        size_t len = 0;
        event = luaL_checklstring(L, index, &len);
        if (len == 0)
        {
            luaL_argerror(L, index, "Facebook Analytics event cannot be empty");
        }
    }
    else
    {
        luaL_argerror(L, index,
            "Facebook Analytics event must be number or string");
    }

    return event;
}

const char* dmFacebookGR::Analytics::GetParameter(lua_State* L, int index, int tableIndex)
{
    const char* parameter = 0;
    if (lua_isnil(L, index))
    {
        luaL_argerror(L, tableIndex, "Facebook Analytics parameter cannot be nil");
    }
    else if (lua_isnumber(L, index))
    {
        unsigned int parameter_number =
            (unsigned int) luaL_checknumber(L, index);
        parameter = ::LookupParameter(parameter_number);
        if (parameter == 0)
        {
            luaL_argerror(L, tableIndex,
                "Facebook Analytics parameter does not exist");
        }
    }
    else if (lua_isstring(L, index))
    {
        size_t len = 0;
        parameter = luaL_checklstring(L, index, &len);
        if (len == 0)
        {
            luaL_argerror(L, tableIndex,
                "Facebook Analytics parameter cannot be empty");
        }
    }
    else
    {
        luaL_argerror(L, tableIndex,
            "Facebook Analytics parameter must be number or string");
    }

    return parameter;
}

void dmFacebookGR::Analytics::GetParameterTable(lua_State* L, int index, const char** keys,
    const char** values, unsigned int* length)
{
    lua_pushvalue(L, index);
    lua_pushnil(L);

    unsigned int position = 0;
    while (lua_next(L, -2) && position < (*length))
    {
        lua_pushvalue(L, -2);
        keys[position] = dmFacebookGR::Analytics::GetParameter(L, -1, index);
        values[position] = lua_tostring(L, -2);
        lua_pop(L, 2);

        if (keys[position] == 0x0)
        {
            dmLogError("Unsupported parameter type for key, must be string or number.");
        }
        else if (values[position] == 0x0)
        {
            dmLogError("Unsupported parameter value type for key '%s', value must be string or number.", keys[position]);
        }
        else
        {
            ++position;
        }
    }

    lua_pop(L, 1);
    *length = position;
}

void dmFacebookGR::Analytics::RegisterConstants(lua_State* L)
{
    // Add constants to table LIB_NAME
    lua_getglobal(L, LIB_NAME);

    #define SETCONSTANT(name, val) \
        lua_pushnumber(L, (lua_Number) val); lua_setfield(L, -2, #name);

    SETCONSTANT(EVENT_ACHIEVED_LEVEL,           dmFacebookGR::Analytics::ACHIEVED_LEVEL);
    SETCONSTANT(EVENT_ADDED_PAYMENT_INFO,       dmFacebookGR::Analytics::ADDED_PAYMENT_INFO);
    SETCONSTANT(EVENT_ADDED_TO_CART,            dmFacebookGR::Analytics::ADDED_TO_CART);
    SETCONSTANT(EVENT_ADDED_TO_WISHLIST,        dmFacebookGR::Analytics::ADDED_TO_WISHLIST);
    SETCONSTANT(EVENT_COMPLETED_REGISTRATION,   dmFacebookGR::Analytics::COMPLETED_REGISTRATION);
    SETCONSTANT(EVENT_COMPLETED_TUTORIAL,       dmFacebookGR::Analytics::COMPLETED_TUTORIAL);
    SETCONSTANT(EVENT_INITIATED_CHECKOUT,       dmFacebookGR::Analytics::INITIATED_CHECKOUT);
    SETCONSTANT(EVENT_PURCHASED,                dmFacebookGR::Analytics::PURCHASED);
    SETCONSTANT(EVENT_RATED,                    dmFacebookGR::Analytics::RATED);
    SETCONSTANT(EVENT_SEARCHED,                 dmFacebookGR::Analytics::SEARCHED);
    SETCONSTANT(EVENT_SPENT_CREDITS,            dmFacebookGR::Analytics::SPENT_CREDITS);
    SETCONSTANT(EVENT_TIME_BETWEEN_SESSIONS,    dmFacebookGR::Analytics::TIME_BETWEEN_SESSIONS);
    SETCONSTANT(EVENT_UNLOCKED_ACHIEVEMENT,     dmFacebookGR::Analytics::UNLOCKED_ACHIEVEMENT);
    SETCONSTANT(EVENT_VIEWED_CONTENT,           dmFacebookGR::Analytics::VIEWED_CONTENT);

    SETCONSTANT(PARAM_CONTENT_ID,               dmFacebookGR::Analytics::CONTENT_ID);
    SETCONSTANT(PARAM_CONTENT_TYPE,             dmFacebookGR::Analytics::CONTENT_TYPE);
    SETCONSTANT(PARAM_CURRENCY,                 dmFacebookGR::Analytics::CURRENCY);
    SETCONSTANT(PARAM_DESCRIPTION,              dmFacebookGR::Analytics::DESCRIPTION);
    SETCONSTANT(PARAM_LEVEL,                    dmFacebookGR::Analytics::LEVEL);
    SETCONSTANT(PARAM_MAX_RATING_VALUE,         dmFacebookGR::Analytics::MAX_RATING_VALUE);
    SETCONSTANT(PARAM_NUM_ITEMS,                dmFacebookGR::Analytics::NUM_ITEMS);
    SETCONSTANT(PARAM_PAYMENT_INFO_AVAILABLE,   dmFacebookGR::Analytics::PAYMENT_INFO_AVAILABLE);
    SETCONSTANT(PARAM_REGISTRATION_METHOD,      dmFacebookGR::Analytics::REGISTRATION_METHOD);
    SETCONSTANT(PARAM_SEARCH_STRING,            dmFacebookGR::Analytics::SEARCH_STRING);
    SETCONSTANT(PARAM_SOURCE_APPLICATION,       dmFacebookGR::Analytics::SOURCE_APPLICATION);
    SETCONSTANT(PARAM_SUCCESS,                  dmFacebookGR::Analytics::SUCCESS);

    #undef SETCONSTANT

    // Pop table LIB_NAME
    lua_pop(L, 1);

}
