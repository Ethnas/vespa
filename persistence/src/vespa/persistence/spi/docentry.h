// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * \class storage::spi::DocEntry
 * \ingroup spi
 *
 * \brief Represents a document with metadata stored.
 *
 * To do merge, all SPI implementations need some common metadata. To do iterate
 * efficiently, we also want options to only return metadata or similar. Thus
 * we need a class to contain all generic parts stored by all SPI
 * implementations.
 */

#pragma once

#include <persistence/spi/types.h>
#include <vespa/document/base/globalid.h>

namespace storage::spi {

enum class DocumentMetaFlags {
    NONE             = 0x0,
    REMOVE_ENTRY     = 0x1
};

class DocEntry {
public:
    using SizeType = uint32_t;
    using UP = std::unique_ptr<DocEntry>;
    using SP = std::shared_ptr<DocEntry>;

    DocEntry(const DocEntry &) = delete;
    DocEntry & operator=(const DocEntry &) = delete;
    DocEntry(DocEntry &&) = delete;
    DocEntry & operator=(DocEntry &&) = delete;
    virtual ~DocEntry();
    bool isRemove() const { return (_metaFlags == DocumentMetaFlags::REMOVE_ENTRY); }
    Timestamp getTimestamp() const { return _timestamp; }
    DocumentMetaFlags getFlags() const { return _metaFlags; }
    /**
     * If entry contains a document, returns its serialized size.
     * If entry contains a document id, returns the serialized size of
     * the id alone.
     * Otherwise (i.e. metadata only), returns sizeof(DocEntry).
     */
    SizeType getSize() const { return _size; }

    virtual vespalib::string toString() const;
    virtual const Document* getDocument() const { return nullptr; }
    virtual const DocumentId* getDocumentId() const { return nullptr; }
    virtual vespalib::stringref getDocumentType() const { return vespalib::stringref(); }
    virtual GlobalId getGid() const { return GlobalId(); }
    virtual DocumentUP releaseDocument();
    static UP create(Timestamp t, DocumentMetaFlags metaFlags);
    static UP create(Timestamp t, DocumentMetaFlags metaFlags, const DocumentId &docId);
    static UP create(Timestamp t, DocumentMetaFlags metaFlags, vespalib::stringref docType, GlobalId gid);
    static UP create(Timestamp t, DocumentUP doc);
    static UP create(Timestamp t, DocumentUP doc, SizeType serializedDocumentSize);
protected:
    DocEntry(Timestamp t, DocumentMetaFlags metaFlags, SizeType size)
        : _timestamp(t),
          _metaFlags(metaFlags),
          _size(size)
    {}
private:
    DocEntry(Timestamp t, DocumentMetaFlags metaFlags) : DocEntry(t, metaFlags, sizeof(DocEntry)) { }
    Timestamp          _timestamp;
    DocumentMetaFlags  _metaFlags;
    SizeType           _size;
};

std::ostream & operator << (std::ostream & os, const DocEntry & r);

}
