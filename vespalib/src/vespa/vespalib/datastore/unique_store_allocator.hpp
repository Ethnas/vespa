// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "unique_store_allocator.h"
#include "datastore.hpp"

namespace search::datastore {

constexpr size_t NUM_ARRAYS_FOR_NEW_UNIQUESTORE_BUFFER = 1024u;
constexpr float ALLOC_GROW_FACTOR = 0.2;

template <typename EntryT, typename RefT>
UniqueStoreAllocator<EntryT, RefT>::UniqueStoreAllocator()
    : ICompactable(),
      _store(),
      _typeHandler(1, 2u, RefT::offsetSize(), NUM_ARRAYS_FOR_NEW_UNIQUESTORE_BUFFER, ALLOC_GROW_FACTOR)
{
    auto typeId = _store.addType(&_typeHandler);
    assert(typeId == 0u);
    _store.initActiveBuffers();
}

template <typename EntryT, typename RefT>
UniqueStoreAllocator<EntryT, RefT>::~UniqueStoreAllocator()
{
    _store.clearHoldLists();
    _store.dropBuffers();
}

template <typename EntryT, typename RefT>
EntryRef
UniqueStoreAllocator<EntryT, RefT>::allocate(const EntryType& value)
{
    return _store.template allocator<WrappedEntryType>(0).alloc(value).ref;
}

template <typename EntryT, typename RefT>
void
UniqueStoreAllocator<EntryT, RefT>::hold(EntryRef ref)
{
    _store.holdElem(ref, 1);
}

template <typename EntryT, typename RefT>
EntryRef
UniqueStoreAllocator<EntryT, RefT>::move(EntryRef ref)
{
    return _store.template allocator<WrappedEntryType>(0).alloc(getWrapped(ref)).ref;
}

}
