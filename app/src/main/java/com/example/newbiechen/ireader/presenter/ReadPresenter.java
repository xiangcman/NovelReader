package com.example.newbiechen.ireader.presenter;

import android.util.Log;

import com.example.newbiechen.ireader.model.bean.BookChapterBean;
import com.example.newbiechen.ireader.model.bean.ChapterInfoBean;
import com.example.newbiechen.ireader.model.local.BookRepository;
import com.example.newbiechen.ireader.model.remote.RemoteRepository;
import com.example.newbiechen.ireader.presenter.contract.ReadContract;
import com.example.newbiechen.ireader.ui.base.RxPresenter;
import com.example.newbiechen.ireader.utils.LogUtils;
import com.example.newbiechen.ireader.utils.MD5Utils;
import com.example.newbiechen.ireader.utils.RxUtils;
import com.example.newbiechen.ireader.widget.page.TxtChapter;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.SingleTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by newbiechen on 17-5-16.
 */

public class ReadPresenter extends RxPresenter<ReadContract.View>
        implements ReadContract.Presenter {
    private static final String TAG = "ReadPresenter";

    private Subscription mChapterSub;

    @Override
    public void loadCategory(String bookId) {
        Log.d(TAG, "loadCategory-->加载整个书的章节信息:" + bookId);
        Disposable disposable = RemoteRepository.getInstance()
                .getBookChapters(bookId)
                //doOnSuccess是数据获成功的回调
                .doOnSuccess(new Consumer<List<BookChapterBean>>() {
                    @Override
                    public void accept(List<BookChapterBean> bookChapterBeen) throws Exception {
                        //进行设定BookChapter所属的书的id。
                        for (BookChapterBean bookChapter : bookChapterBeen) {
                            bookChapter.setId(MD5Utils.strToMd5By16(bookChapter.getLink()));
                            bookChapter.setBookId(bookId);
                            Log.d(TAG, "bookChapter:" + bookChapter.toString());
                        }
                    }
                }).compose(new SingleTransformer<List<BookChapterBean>, List<BookChapterBean>>() {
                    @Override
                    public SingleSource<List<BookChapterBean>> apply(Single<List<BookChapterBean>> upstream) {
                        return RxUtils.toSimpleSingle(upstream);
                    }
                }).subscribe(new Consumer<List<BookChapterBean>>() {
                                 @Override
                                 public void accept(List<BookChapterBean> beans) throws Exception {
                                     mView.showCategory(beans);
                                 }
                             }, new Consumer<Throwable>() {
                                 @Override
                                 public void accept(Throwable e) throws Exception {
                                     //TODO: Haven't grate conversation method.
                                     LogUtils.e(e);
                                 }
                             }
                );
        addDisposable(disposable);
    }

    /**
     * 传入要加载哪几个章节的信息
     *
     * @param bookId
     * @param bookChapters
     */
    @Override
    public void loadChapter(String bookId, List<TxtChapter> bookChapters) {
        Log.d(TAG, "loadChapter-->加载某几个章节的章节信息:" + bookId);
        Log.d(TAG, "bookChapters.size:" + bookChapters.size());
        int size = bookChapters.size();
        //取消上次的任务，防止多次加载
        if (mChapterSub != null) {
            mChapterSub.cancel();
        }

        List<Single<ChapterInfoBean>> chapterInfos = new ArrayList<>(bookChapters.size());
        //队列先进先出的特点
        ArrayDeque<String> titles = new ArrayDeque<>(bookChapters.size());

        // 将要下载章节，转换成网络请求。
        for (int i = 0; i < size; ++i) {
            TxtChapter bookChapter = bookChapters.get(i);
            // 网络中获取数据
            Single<ChapterInfoBean> chapterInfoSingle = RemoteRepository.getInstance()
                    .getChapterInfo(bookChapter.getLink());

            chapterInfos.add(chapterInfoSingle);

            titles.add(bookChapter.getTitle());//第一章、第二章、第三章类似这种顺序
        }
        //single.concat将多个被观察者绑定到一起
        Single.concat(chapterInfos)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        new Subscriber<ChapterInfoBean>() {
                            String title = titles.poll();

                            @Override
                            public void onSubscribe(Subscription s) {
                                s.request(Integer.MAX_VALUE);
                                mChapterSub = s;
                            }

                            @Override
                            public void onNext(ChapterInfoBean chapterInfoBean) {
                                //存储数据
                                BookRepository.getInstance().saveChapterInfo(
                                        bookId, title, chapterInfoBean.getBody()
                                );
                                mView.finishChapter();
                                //将获取到的数据进行存储
                                title = titles.poll();
                            }

                            @Override
                            public void onError(Throwable t) {
                                //只有第一个加载失败才会调用errorChapter
                                if (bookChapters.get(0).getTitle().equals(title)) {
                                    mView.errorChapter();
                                }
                                LogUtils.e(t);
                            }

                            @Override
                            public void onComplete() {
                            }
                        }
                );
    }

    @Override
    public void detachView() {
        super.detachView();
        if (mChapterSub != null) {
            mChapterSub.cancel();
        }
    }

}
