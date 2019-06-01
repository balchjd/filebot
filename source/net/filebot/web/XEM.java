package net.filebot.web;

import static java.util.Arrays.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static net.filebot.util.JsonUtilities.*;
import static net.filebot.util.StringUtilities.*;
import static net.filebot.web.WebRequest.*;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.filebot.Cache;
import net.filebot.CacheType;
import net.filebot.Resource;

public enum XEM {

	AniDB, TheTVDB;

	public String getOriginName() {
		switch (this) {
		case AniDB:
			return "anidb";
		case TheTVDB:
			return "tvdb";
		}
		return null;
	}

	public Integer getSeason(Integer s) {
		return this == AniDB ? 1 : s;
	}

	protected final Resource<Set<Integer>> haveMap = Resource.lazy(this::getHaveMap);

	public Optional<Episode> map(Episode episode, XEM destination) throws Exception {
		Integer seriesId = episode.getSeriesInfo().getId();

		if (!haveMap.get().contains(seriesId)) {
			return Optional.empty();
		}

		String seriesName = episode.getSeriesName();
		Integer season = getSeason(episode.getSeason());

		Map<String, List<String>> names = getNames(seriesId);

		Integer mappedSeason = names.entrySet().stream().filter(it -> {
			return it.getValue().contains(seriesName);
		}).map(it -> {
			return matchInteger(it.getKey());
		}).filter(Objects::nonNull).findFirst().orElse(season);

		String mappedSeriesName = names.get("all").get(0);

		Map<String, Map<String, Number>> mapping = episode.getEpisode() != null ? getSingle(seriesId, mappedSeason, episode.getEpisode()) : getSingle(seriesId, 0, episode.getSpecial());

		List<Episode> mappedEpisode = mapping.entrySet().stream().filter(it -> {
			return it.getKey().startsWith(destination.getOriginName());
		}).map(it -> {
			Map<String, Number> mappedNumbers = it.getValue();

			Integer e = getInteger(mappedNumbers, "episode");
			Integer a = getInteger(mappedNumbers, "absolute");

			return episode.derive(mappedSeriesName, mappedSeason, e, a);
		}).collect(toList());

		if (mappedEpisode.size() == 1) {
			return Optional.of(mappedEpisode.get(0));
		} else if (mappedEpisode.size() > 1) {
			return Optional.of(new MultiEpisode(mappedEpisode));
		}

		return Optional.empty();
	}

	public List<Map<String, Map<String, Integer>>> getAll(Integer id) throws Exception {
		Map<String, Object> parameters = new LinkedHashMap<>(2);
		parameters.put("origin", getOriginName());
		parameters.put("id", id);

		Object response = request("all", parameters);
		return (List) asList(getArray(response, "data"));
	}

	public Map<String, Map<String, Number>> getSingle(Integer id, Integer season, Integer episode) throws Exception {
		Map<String, Object> parameters = new LinkedHashMap<>(4);
		parameters.put("origin", getOriginName());
		parameters.put("id", id);
		parameters.put("season", season);
		parameters.put("episode", episode);

		Object response = request("single", parameters);
		return (Map) getMap(response, "data");
	}

	public List<SearchResult> getAllNames() throws Exception {
		return getAllNames(null, null, true);
	}

	public List<SearchResult> getAllNames(Integer season, String language, boolean defaultNames) throws Exception {
		List<SearchResult> result = new ArrayList<>();

		Map<String, Object> parameters = new LinkedHashMap<>(4);
		parameters.put("origin", getOriginName());
		parameters.put("season", season);
		parameters.put("language", language);
		parameters.put("defaultNames", defaultNames ? "1" : "0");

		Object response = request("allNames", parameters);

		getMap(response, "data").forEach((k, v) -> {
			int id = Integer.parseInt(k.toString());
			List<String> names = stream(asArray(v)).filter(Objects::nonNull).map(Objects::toString).filter(s -> s.length() > 0).collect(toList());

			if (names.size() > 0) {
				result.add(new SearchResult(id, names.get(0), names.subList(1, names.size())));
			}
		});

		return result;
	}

	public Set<Integer> getHaveMap() throws Exception {
		Map<String, Object> parameters = new LinkedHashMap<>(1);
		parameters.put("origin", getOriginName());

		Object response = request("havemap", parameters);
		return stream(getArray(response, "data")).map(Object::toString).map(Integer::parseInt).collect(toSet());
	}

	public Map<String, List<String>> getNames(Integer id) throws Exception {
		Map<String, Object> parameters = new LinkedHashMap<>(3);
		parameters.put("origin", getOriginName());
		parameters.put("id", id);
		parameters.put("defaultNames", "1");

		Object response = request("names", parameters);
		Map<String, List<String>> names = new HashMap<>();

		getMap(response, "data").forEach((k, v) -> {
			names.put(k.toString(), asNamesList(v));
		});

		return names;
	}

	private List<String> asNamesList(Object value) {
		if (value instanceof Map) {
			Map<Object, Object[]> names = (Map) value;
			return names.values().stream().flatMap(a -> stream(a)).map(Object::toString).collect(toList());
		} else if (value instanceof Collection) {
			Collection<Object> names = (Collection) value;
			return names.stream().map(Object::toString).collect(toList());
		}
		return singletonList(value.toString());
	}

	protected Object request(String path, Map<String, Object> parameters) throws Exception {
		return request(path + '?' + encodeParameters(parameters, true));
	}

	protected Object request(String path) throws Exception {
		return getCache().json(path, this::getResource).expire(Cache.ONE_WEEK).get();
	}

	protected URL getResource(String path) throws Exception {
		return new URL("http://thexem.de/map/" + path);
	}

	protected Cache getCache() {
		return Cache.getCache("xem", CacheType.Monthly);
	}

	public static List<String> names() {
		return stream(values()).map(Enum::name).collect(toList());
	}

	public static XEM forName(String name) {
		for (XEM db : values()) {
			if (db.name().equalsIgnoreCase(name) || db.getOriginName().equalsIgnoreCase(name)) {
				return db;
			}
		}

		throw new IllegalArgumentException(String.format("XEM not supported: %s not in %s", name, asList(values())));
	}

}
