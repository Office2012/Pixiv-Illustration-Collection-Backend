package dev.cheerfun.pixivic.crawler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cheerfun.pixivic.common.model.Artist;
import dev.cheerfun.pixivic.crawler.dto.ArtistDTO;
import dev.cheerfun.pixivic.crawler.mapper.ArtistMapper;
import dev.cheerfun.pixivic.crawler.util.HttpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author OysterQAQ
 * @version 1.0
 * @date 2019/08/10 21:22
 * @description ArtistService
 */
@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ArtistService {
    private final HttpUtil httpUtil;
    private final ObjectMapper objectMapper;
    private final ArtistMapper artistMapper;
    private ReentrantLock lock = new ReentrantLock();

    private ArrayList<Integer> waitForReDownload = new ArrayList<>();

    public void pullArtistsInfo(List<Integer> artistIds) throws InterruptedException {
        List<Integer> artistIdsToDownload = artistMapper.queryArtistsNotInDb(artistIds);
        int taskSum = artistIds.size();
        ArrayList<Artist> artists = new ArrayList<>(taskSum);
        final CountDownLatch cd = new CountDownLatch(taskSum);
        artistIds.stream().parallel().forEach(i -> {
            httpUtil.getJson("https://app-api.pixiv.net/v1/user/detail?user_id=" + artistIdsToDownload.get(i) + "&filter=for_ios")
                    .orTimeout(1000 * 10L, TimeUnit.MILLISECONDS).whenComplete((result, throwable) -> {
                if ("false".equals(result)) {
                    this.addToWaitingList(i);
                }
                try {
                    artists.add(i, ArtistDTO.castToArtist(objectMapper.readValue(result, new TypeReference<ArtistDTO>() {
                    })));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                cd.countDown();
            });
        });
        cd.await(taskSum, TimeUnit.SECONDS);
        artists.removeIf(Objects::isNull);
        // this.dealReDownload();
        if (artists.size() != 0)
            artistMapper.insert(artists);
    }

    private void dealReDownload() throws InterruptedException {
        final CountDownLatch cd = new CountDownLatch(waitForReDownload.size());
        waitForReDownload.forEach(i -> httpUtil.getJson("https://app-api.pixiv.net/v1/user/detail?user_id=" + i + "&filter=for_ios").thenAccept(s -> cd.countDown()));
        cd.await(waitForReDownload.size() * 11, TimeUnit.SECONDS);
    }

    private void addToWaitingList(int id) {
        try {
            lock.lock();
            waitForReDownload.add(id);
        } finally {
            lock.unlock();
        }
    }
}
