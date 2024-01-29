// Copyright 2020-2024 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.


#include <stdio.h> // printf

#include <dmsdk/dlib/array.h>


#include <dmsdk/dlib/array.h>
#include <dmsdk/dlib/atomic.h>
#include <dmsdk/dlib/profile.h>
#include <dmsdk/dlib/log.h>

#if !defined(__EMSCRIPTEN__)
    #define DM_HAS_THREADS
#endif

#if defined(DM_USE_SINGLE_THREAD)
    #if defined(DM_HAS_THREADS)
        #undef DM_HAS_THREADS
    #endif
#endif

#if defined(DM_HAS_THREADS)
    #include <dmsdk/dlib/condition_variable.h>
    #include <dmsdk/dlib/mutex.h>
    #include <dmsdk/dlib/thread.h>
#endif

#include "jc/ringbuffer.h"
#include "job_thread.h"

namespace dmJobThread
{

struct JobItem
{
    void*       m_Context;
    void*       m_Data;
    FProcess    m_Process;
    FCallback   m_Callback;
    int         m_Result;
};

struct JobThreadContext
{
    jc::RingBuffer<JobItem>                 m_Work;
    jc::RingBuffer<JobItem>                 m_Done;

#if defined(DM_HAS_THREADS)
    dmMutex::HMutex                         m_Mutex;
    dmConditionVariable::HConditionVariable m_WakeupCond;
    int32_atomic_t                          m_Run;
#endif
};

struct JobContext
{
#if defined(DM_HAS_THREADS)
    dmArray<dmThread::Thread> m_Threads;
#endif
    JobThreadContext    m_ThreadContext;
};

static void PutWork(JobThreadContext* ctx, const JobItem* item)
{
#if defined(DM_HAS_THREADS)
    DM_MUTEX_SCOPED_LOCK(ctx->m_Mutex);
#endif
    if (ctx->m_Work.Full())
        ctx->m_Work.SetCapacity(ctx->m_Work.Capacity() + 8);
    ctx->m_Work.Push(*item);
}

static void PutDone(JobThreadContext* ctx, JobItem* item)
{
#if defined(DM_HAS_THREADS)
    DM_MUTEX_SCOPED_LOCK(ctx->m_Mutex);
#endif
    if (ctx->m_Done.Full())
        ctx->m_Done.SetCapacity(ctx->m_Done.Capacity() + 8);
    ctx->m_Done.Push(*item);
}

#if defined(DM_HAS_THREADS)
static void JobThread(void* _ctx)
{
    JobThreadContext* ctx = (JobThreadContext*)_ctx;
    while (dmAtomicGet32(&ctx->m_Run) != 0)
    {
        JobItem item;
        {
            DM_MUTEX_SCOPED_LOCK(ctx->m_Mutex);
            while(ctx->m_Work.Empty())
            {
                dmConditionVariable::Wait(ctx->m_WakeupCond, ctx->m_Mutex);

                if(dmAtomicGet32(&ctx->m_Run) == 0)
                    return;
            }
            item = ctx->m_Work.Pop();
        }

        item.m_Result = item.m_Process(item.m_Context, item.m_Data);
        PutDone(ctx, &item);
    }
}
#else
static void UpdateSingleThread(JobThreadContext* ctx)
{
    if (ctx->m_Work.Empty())
        return;
    // TODO: Perhaps time scope a number of items!
    JobItem item = ctx->m_Work.Pop();

    item.m_Result = item.m_Process(item.m_Context, item.m_Data);
    PutDone(ctx, &item);
}
#endif

HContext Create(uint8_t thread_count, const JobThreadCreationParams* create_params)
{
    JobContext* context = new JobContext;
#if defined(DM_HAS_THREADS)
    context->m_ThreadContext.m_Mutex = dmMutex::New();
    context->m_ThreadContext.m_WakeupCond = dmConditionVariable::New();
    context->m_ThreadContext.m_Run = 1;

    context->m_Threads.SetCapacity(thread_count);
    context->m_Threads.SetSize(thread_count);

    for (int i = 0; i < thread_count; ++i)
    {
        context->m_Threads[i] = dmThread::New(JobThread, 0x80000, (void*)&context->m_ThreadContext, create_params[i].m_ThreadName);
    }
#endif
    return context;
}

void Destroy(HContext context)
{
    if (!context)
        return;

#if defined(DM_HAS_THREADS)
    dmAtomicStore32(&context->m_ThreadContext.m_Run, 0);
    {
        DM_MUTEX_SCOPED_LOCK(context->m_ThreadContext.m_Mutex);
        // Wake up the worker threads so it can exit and allow us to join
        for (int i = 0; i < context->m_Threads.Size(); ++i)
        {
            dmConditionVariable::Signal(context->m_ThreadContext.m_WakeupCond);
        }
    }

    for (int i = 0; i < context->m_Threads.Size(); ++i)
    {
        dmThread::Join(context->m_Threads[i]);
    }
    dmConditionVariable::Delete(context->m_ThreadContext.m_WakeupCond);
    dmMutex::Delete(context->m_ThreadContext.m_Mutex);
#endif // DM_HAS_THREADS

    delete context;
}

void PushJob(HContext context, FProcess process, FCallback callback, void* user_context, void* data)
{
    JobItem item;
    item.m_Context = user_context;
    item.m_Data = data;
    item.m_Process = process;
    item.m_Callback = callback;
    item.m_Result = 0;

    PutWork(&context->m_ThreadContext, &item);
#if defined(DM_HAS_THREADS)
    dmConditionVariable::Signal(context->m_ThreadContext.m_WakeupCond);
#endif
}

void Update(HContext context)
{
    DM_PROFILE("Update");

#if !defined(DM_HAS_THREADS)
    UpdateSingleThread(&context->m_ThreadContext);
#endif

    // Lock for as little as possible, by copying the items to an array owned by this thread
    uint32_t size;
    dmArray<JobItem> items;

    {
#if defined(DM_HAS_THREADS)
        DM_MUTEX_SCOPED_LOCK(context->m_ThreadContext.m_Mutex);
#endif
        size = context->m_ThreadContext.m_Done.Size();
        items.SetCapacity(size);

        for(uint32_t i = 0; i < size; ++i)
            items.Push(context->m_ThreadContext.m_Done[i]);
        context->m_ThreadContext.m_Done.Clear();
    }

    // Now do the callbacks
    for(uint32_t i = 0; i < size; ++i)
    {
        JobItem& item = items[i];
        if (item.m_Callback)
            item.m_Callback(item.m_Context, item.m_Data, item.m_Result);
    }
}

bool PlatformHasThreadSupport()
{
#if defined(DM_HAS_THREADS)
    return true;
#else
    return false;
#endif
}

} // namespace dmJobThread
