// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "distancefeature.h"
#include <vespa/searchcommon/common/schema.h>
#include <vespa/searchlib/fef/location.h>
#include <vespa/searchlib/fef/matchdata.h>
#include <vespa/document/datatype/positiondatatype.h>
#include <vespa/vespalib/geo/zcurve.h>
#include <vespa/vespalib/util/stash.h>
#include <cmath>
#include <limits>
#include "utils.h"

#include <vespa/log/log.h>
LOG_SETUP(".features.distancefeature");

using namespace search::fef;
using namespace search::index::schema;

namespace search::features {

/** Implements the executor for converting NNS rawscore to a distance feature. */
class ConvertRawscoreToDistance : public fef::FeatureExecutor {
private:
    std::vector<fef::TermFieldHandle> _handles;
    const fef::MatchData             *_md;
    void handle_bind_match_data(const fef::MatchData &md) override {
        _md = &md;
    }
public:
    ConvertRawscoreToDistance(const fef::IQueryEnvironment &env, uint32_t fieldId);
    ConvertRawscoreToDistance(const fef::IQueryEnvironment &env, const vespalib::string &label);
    void execute(uint32_t docId) override;
};

ConvertRawscoreToDistance::ConvertRawscoreToDistance(const fef::IQueryEnvironment &env, uint32_t fieldId)
  : _handles(),
    _md(nullptr)
{
    _handles.reserve(env.getNumTerms());
    for (uint32_t i = 0; i < env.getNumTerms(); ++i) {
        search::fef::TermFieldHandle handle = util::getTermFieldHandle(env, i, fieldId);
        if (handle != search::fef::IllegalHandle) {
            _handles.push_back(handle);
        }
    }
}

ConvertRawscoreToDistance::ConvertRawscoreToDistance(const fef::IQueryEnvironment &env, const vespalib::string &label)
  : _handles(),
    _md(nullptr)
{
    const ITermData *term = util::getTermByLabel(env, label);
    if (term != nullptr) {
        // expect numFields() == 1
        for (uint32_t i = 0; i < term->numFields(); ++i) {
            TermFieldHandle handle = term->field(i).getHandle();
            if (handle != IllegalHandle) {
                _handles.push_back(handle);
            }
        }
    }
}

void
ConvertRawscoreToDistance::execute(uint32_t docId)
{
    feature_t min_distance = std::numeric_limits<feature_t>::max();
    assert(_md);
    for (auto handle : _handles) {
        const TermFieldMatchData *tfmd = _md->resolveTermField(handle);
        if (tfmd->getDocId() == docId) {
            feature_t invdist = tfmd->getRawScore();
            feature_t converted = (1.0 / invdist) - 1.0;
            min_distance = std::min(min_distance, converted);
        }
    }
    outputs().set_number(0, min_distance);
}


feature_t
DistanceExecutor::calculateDistance(uint32_t docId)
{
    if (_location.isValid() && _pos != nullptr) {
        return calculate2DZDistance(docId);
    }
    return DEFAULT_DISTANCE;
}


feature_t
DistanceExecutor::calculate2DZDistance(uint32_t docId)
{
    _intBuf.fill(*_pos, docId);
    uint32_t numValues = _intBuf.size();
    uint64_t sqabsdist = std::numeric_limits<uint64_t>::max();
    int32_t docx = 0;
    int32_t docy = 0;
    for (uint32_t i = 0; i < numValues; ++i) {
        vespalib::geo::ZCurve::decode(_intBuf[i], &docx, &docy);
        uint32_t dx;
        uint32_t dy;
        if (_location.getXPosition() > docx) {
            dx = _location.getXPosition() - docx;
        } else {
            dx = docx - _location.getXPosition();
        }
        if (_location.getXAspect() != 0) {
            dx = ((uint64_t) dx * _location.getXAspect()) >> 32;
        }
        if (_location.getYPosition() > docy) {
            dy = _location.getYPosition() - docy;
        } else {
            dy = docy - _location.getYPosition();
        }
        uint64_t sqdist = (uint64_t) dx * dx + (uint64_t) dy * dy;
        if (sqdist < sqabsdist) {
            sqabsdist = sqdist;
        }
    }
    return static_cast<feature_t>(std::sqrt(static_cast<feature_t>(sqabsdist)));
}

DistanceExecutor::DistanceExecutor(const Location & location,
                                   const search::attribute::IAttributeVector * pos) :
    FeatureExecutor(),
    _location(location),
    _pos(pos),
    _intBuf()
{
    if (_pos != nullptr) {
        _intBuf.allocate(_pos->getMaxValueCount());
    }
}

void
DistanceExecutor::execute(uint32_t docId)
{
    outputs().set_number(0, calculateDistance(docId));
}

const feature_t DistanceExecutor::DEFAULT_DISTANCE(6400000000.0);


DistanceBlueprint::DistanceBlueprint() :
    Blueprint("distance"),
    _arg_string(),
    _attr_id(search::index::Schema::UNKNOWN_FIELD_ID),
    _use_geo_pos(false),
    _use_nns_tensor(false),
    _use_item_label(false)
{
}

DistanceBlueprint::~DistanceBlueprint() = default;

void
DistanceBlueprint::visitDumpFeatures(const IIndexEnvironment &,
                                     IDumpFeatureVisitor &) const
{
}

Blueprint::UP
DistanceBlueprint::createInstance() const
{
    return std::make_unique<DistanceBlueprint>();
}

bool
DistanceBlueprint::setup_geopos(const IIndexEnvironment & env,
                                const vespalib::string &attr)
{
    _arg_string = attr;
    _use_geo_pos = true;
    describeOutput("out", "The euclidean distance from the query position.");
    env.hintAttributeAccess(_arg_string);
    return true;
}

bool
DistanceBlueprint::setup_nns(const IIndexEnvironment & env,
                             const vespalib::string &attr)
{
    _arg_string = attr;
    _use_nns_tensor = true;
    describeOutput("out", "The euclidean distance from the query position.");
    env.hintAttributeAccess(_arg_string);
    return true;
}

bool
DistanceBlueprint::setup(const IIndexEnvironment & env,
                         const ParameterList & params)
{
    // params[0] = attribute name
    vespalib::string arg = params[0].getValue();
    bool allow_bad_field = true;
    if (params.size() == 2) {
        // params[0] = field / label
        // params[0] = attribute name / label value
        if (arg == "label") {
            _arg_string = params[1].getValue();
            _use_item_label = true;
            describeOutput("out", "The euclidean distance from the labeled query item.");
            return true;
        } else if (arg == "field") {
            arg = params[1].getValue();
            allow_bad_field = false;
        } else {
            LOG(error, "first argument must be 'field' or 'label', but was '%s'",
                arg.c_str());
            return false;
        }
    }
    const FieldInfo *fi = env.getFieldByName(arg);
    if (fi != nullptr && fi->hasAttribute()) {
        auto dt = fi->get_data_type();
        auto ct = fi->collection();
        if (dt == DataType::TENSOR && ct == CollectionType::SINGLE) {
            _attr_id = fi->id();
            return setup_nns(env, arg);
        }
        // could check if dt is DataType::INT64
        // could check if ct is CollectionType::SINGLE or CollectionType::ARRAY)
        return setup_geopos(env, arg);
    }
    vespalib::string z = document::PositionDataType::getZCurveFieldName(arg);
    fi = env.getFieldByName(z);
    if (fi != nullptr && fi->hasAttribute()) {
        return setup_geopos(env, z);
    }
    if (allow_bad_field) {
        // backwards compatibility fallback:
        return setup_geopos(env, arg);
    }
    if (env.getFieldByName(arg) == nullptr && fi == nullptr) {
        LOG(error, "unknown field '%s' for rank feature %s\n", arg.c_str(), getName().c_str());
    } else {
        LOG(error, "field '%s' must be an attribute for rank feature %s\n", arg.c_str(), getName().c_str());
    }
    return false;
}

FeatureExecutor &
DistanceBlueprint::createExecutor(const IQueryEnvironment &env, vespalib::Stash &stash) const
{
    if (_use_nns_tensor) {
        return stash.create<ConvertRawscoreToDistance>(env, _attr_id);
    }
    if (_use_item_label) {
        return stash.create<ConvertRawscoreToDistance>(env, _arg_string);
    }
    const search::attribute::IAttributeVector * pos = nullptr;
    const Location & location = env.getLocation();
    LOG(debug, "DistanceBlueprint::createExecutor location.valid='%s', attribute='%s'",
        location.isValid() ? "true" : "false", _arg_string.c_str());
    if (_use_geo_pos && location.isValid()) {
        pos = env.getAttributeContext().getAttribute(_arg_string);
        if (pos != nullptr) {
            if (!pos->isIntegerType()) {
                LOG(warning, "The position attribute '%s' is not an integer attribute. Will use default distance.",
                    pos->getName().c_str());
                pos = nullptr;
            } else if (pos->getCollectionType() == attribute::CollectionType::WSET) {
                LOG(warning, "The position attribute '%s' is a weighted set attribute. Will use default distance.",
                    pos->getName().c_str());
                pos = nullptr;
            }
        } else {
            LOG(warning, "The position attribute '%s' was not found. Will use default distance.", _arg_string.c_str());
        }
    }

    return stash.create<DistanceExecutor>(location, pos);
}

}
