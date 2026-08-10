package com.reactor.rust.smoke;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.projection.GenerateProjectionReader;
import com.reactor.rust.cache.projection.ProjectionIdRead;
import com.reactor.rust.cache.projection.ProjectionMetaRead;

@GenerateProjectionReader(rootPrefix = "smoke.catalog", generatedName = "GeneratedSmokeCacheReader")
public interface SmokeCacheReader {

    @ProjectionIdRead(projection = "detail")
    CacheReadResult detail(long id);

    @ProjectionMetaRead(projection = "meta")
    CacheReadResult metadata();
}
