package com.cnv.cms.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cnv.cms.event.EventModel;
import com.cnv.cms.event.EventProducer;
import com.cnv.cms.event.EventType;
import com.cnv.cms.exception.CmsException;
import com.cnv.cms.mapper.ArticleMapper;
import com.cnv.cms.mapper.AttachmentMapper;
import com.cnv.cms.mapper.UserMapper;
import com.cnv.cms.model.Article;
import com.cnv.cms.model.Channel;
import com.cnv.cms.model.User;
import com.cnv.cms.service.ArticleService;
import com.cnv.cms.service.AttachmentService;
import com.cnv.cms.service.CacheService;
import com.cnv.cms.service.ChannelService;
import com.cnv.cms.service.UserService;
import com.cnv.cms.util.RedisKeyUtil;

@Service("articleServiceImpl")  
public class ArticleServiceImpl implements ArticleService {
	@Autowired
	private ArticleMapper articleMapper;
	@Autowired
	private AttachmentMapper attachMapper;
	@Autowired
	private UserMapper userMapper;
	@Autowired
	private UserService userService;
	@Autowired
	private ChannelService channelService;
    @Autowired  
    private RedisTemplate<String,String> redisTemplate;  
    @Autowired
    private EventProducer eventProducer;
    
    @Autowired
    private CacheService cacheService;
	
	@Autowired
	@Qualifier("attachServiceImpl")
	private AttachmentService attachService;
	
	@Transactional
	public boolean add(Article t) {
		Integer id = articleMapper.maxId();
		if(id==null) id=0;
		t.setId(id+1);
		try {
			articleMapper.add(t);
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		
		//逐个更新
		for(int at : t.getAttachs()){
			try {
				attachMapper.setArticleId(at, t.getId());
			} catch (Exception e) {
				e.printStackTrace();
				return false;
			}
		}
		
		return true;
	}
	@Transactional
	public boolean add(Article t, String client) {
		
		if(!add(t)){
			//发生错误把文章删除
			articleMapper.delete(t.getChannelId());
			//删除临时附件
			List<Integer> attachTempIDs = attachService.getTempAttachs(client);
			if (attachTempIDs != null) {
				for (int at : attachTempIDs) {
					attachService.delete(at);
				} 
			}
			attachService.removeTempAttachs(client);
			return false;
		}
		
		try {
			List<Integer> attachIDs = t.getAttachs();
			List<Integer> attachTempIDs = attachService.getTempAttachs(client);
			if (attachTempIDs != null) {
				//如果临时附件不在提交的附件id列表中，则删除
				for (int at : attachTempIDs) {
					if (!attachIDs.contains(at)) {
						attachService.delete(at);
					}
				} 
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		attachService.removeTempAttachs(client);
		
		//发布文章事件，相应的事件监听器进行处理
		EventModel event = new EventModel();
		
		event.setOwnerId(t.getUserId())
			.setEventType(EventType.NEWS_PUBLISH)
			.addExtData("articleId", t.getId())
			.addExtData("articleTitle", t.getTitle());
		eventProducer.addEvent(event);
		
		return true;
	}
	@Transactional
	public boolean delete(int id) {
		
		try {
			//删除Article
			articleMapper.delete(id);
			//删除附件
			attachMapper.deleteByArticleId(id);
			
		} catch (Exception e) {
			return false;
		}
		cacheService.removeCache("articlesCache", "selectById"+id);
		return true;
	}
	@Transactional
	public boolean update(Article t) throws CmsException{
		// TODO 需要更新附件
		Article aOld = articleMapper.selectById(t.getId());
		if(aOld==null){
			throw new CmsException("文章不存在");
		}
		List<Integer> attachIDs = t.getAttachs();
		List<Integer> attachOldIDs = attachMapper.selectIdsByArticleId(t.getId());
		//如果原有附件不在新的附件列表中，则删除
		for(int at : attachOldIDs){
			if(!attachIDs.contains(at)){
				attachMapper.delete(at);
			}
		}
		//如果新的附件不在原有附件列表中，则更新
		for(int at : attachIDs){
			if(!attachOldIDs.contains(at)){
				attachMapper.setArticleId(at, t.getId());
			}
		}
		try {
			articleMapper.update(t);
		} catch (Exception e) {
			return false;
		}
		cacheService.removeCache("articlesCache", "selectById"+t.getId());
		return true;
	}
	//@Cacheable(value="articlesCache",key="#root.methodName+#id")
	public Article selectById(int id) {
		//缓存
		Article at = cacheService.getCache("articlesCache", "selectById"+id, Article.class);
		if(at != null)
			return at;
		
		at = articleMapper.selectById(id);
		if(at == null) throw new CmsException("文章不存在");
		
		//  查询附件
		at.setAttachs(attachMapper.selectIdsByArticleId(id));
		at.setAuthor(userMapper.selectUserByID(at.getUserId()).getUsername());
		
		cacheService.addCache("articlesCache", "selectById"+id, 600, at);
		return at;
	}
	@Transactional
	public void deleteByChannel(int id) {
		try {
			//List<Article> articles = articleMapper.selectByChannel(id);
			//删除Article
			articleMapper.deleteByChannel(id);
			//删除附件
			attachService.deleteByChannelId(id);

		} catch (Exception e) {
			throw new CmsException("文章删除失败");
		}
	}
	@Transactional
	public void deleteByUser(int userId) {
		// TODO Auto-generated method stub
		try {
			List<Article> articles = articleMapper.selectByUserId(userId);
			//删除Article
			articleMapper.deleteByUser(userId);
			//删除附件
			for(Article a: articles){
				attachService.deleteByArticleId(a.getId());
			}

		} catch (Exception e) {
			throw new CmsException("文章删除失败");
		}
	}
	public void changeStatus(int id) {
		articleMapper.changeStatus(id);
	}

	public void addReadTimes(int id, int n) {
		articleMapper.addReadTimes(id, n);
	}

	public void addFellows(int id, int n) {
		articleMapper.addFellows(id, n);
	}

	public List<Article> selectPage(int page, int n) {
		return articleMapper.selectFromTo(n*(page-1), n);
	}
	@Override
	public List<Article> selectByUserId(int id) {
		// TODO Auto-generated method stub
		List<Article> list = articleMapper.selectByUserId(id);
		this.fillArticleList(list);
		return list;
	}

	public List<Article> selectByTitle(String title) {
		// TODO Auto-generated method stub
		List<Article> ats = articleMapper.selectByTitle(title);
		return ats;
	}

	public List<Article> selectByChannel(int id) {
		// TODO Auto-generated method stub
		return articleMapper.selectByChannel(id);
	}

	public List<Article> selectByKeywords(String keywords) {
		// TODO Auto-generated method stub
		return articleMapper.selectByKeywords(keywords);
	}

	public List<Article> selectRecommends() {
		// TODO Auto-generated method stub
		return articleMapper.selectRecommends();
	}

	public List<Article> selectRecommends(int channelId) {
		// TODO Auto-generated method stub
		return articleMapper.selectRecommendsInChannel(channelId);
	}
	//@Cacheable(value="articlesCache",key="#root.methodName+#root.args")
	public List<Article> selectTopRead(int n) {
		// TODO Auto-generated method stub

		List<Article> list = articleMapper.selectTopRead(n);
		List<String> keys = new ArrayList<>();
		for(Article a : list){
			int id = a.getId();
			String key = RedisKeyUtil.getPVKey("article", id);
			keys.add(key);
			
		}
		List<String> values = redisTemplate.opsForValue().multiGet(keys);
		for(int i=0; i<list.size(); i++){
			int pv = values.get(i)== null? 0 : Integer.parseInt(values.get(i));
			list.get(i).setReadTimes(pv);
		}
		this.fillArticleList(list);
		return list;
	}

	public List<Article> selectTopRead(int n, int channelId) {
		// TODO Auto-generated method stub
		List<Article> list = articleMapper.selectTopReadInChannel(n, channelId);
		List<String> keys = new ArrayList<>();
		for(Article a : list){
			int id = a.getId();
			String key = RedisKeyUtil.getPVKey("article", id);
			keys.add(key);
			
		}
		List<String> values = redisTemplate.opsForValue().multiGet(keys);
		for(int i=0; i<list.size(); i++){
			int pv = values.get(i)== null? 0 : Integer.parseInt(values.get(i));
			list.get(i).setReadTimes(pv);
		}
		this.fillArticleList(list);
		return list;
	}

	public List<Article> selectTopFellow(int n) {
		// TODO Auto-generated method stub
		return articleMapper.selectTopFellow(n);
	}
	@Override
	public List<Article> selectPage(int page, int n, int channelId) {
		// TODO Auto-generated method stub
		List<Article> list = articleMapper.selectFromToInChannel(n*(page-1), n,channelId);
		List<String> keys = new ArrayList<>();
		for(Article a : list){
			int id = a.getId();
			String key = RedisKeyUtil.getPVKey("article", id);
			keys.add(key);
			
		}
		List<String> values = redisTemplate.opsForValue().multiGet(keys);
		for(int i=0; i<list.size(); i++){
			int pv = values.get(i)== null? 0 : Integer.parseInt(values.get(i));
			list.get(i).setReadTimes(pv);
		}
		this.fillArticleList(list);
		return  list;
	}
	@Override
	public List<Article> selectFromUserList(Set<String> userIds, int offset, int num) {
		// TODO Auto-generated method stub
		if(userIds == null || userIds.size()==0)
			return null;
		List<String> userList = new ArrayList<>(userIds);
		Map<String, Object> params = new HashMap<String, Object>(2);
		params.put("users", userList);
		params.put("offset", offset);
		params.put("num", num);
		List<Article> list = articleMapper.selectFromUserList(params);
		if(list==null || list.size()==0)
			return null;
		List<String> keys = new ArrayList<>();
		for(Article a : list){
			int id = a.getId();
			String key = RedisKeyUtil.getPVKey("article", id);
			keys.add(key);
			
		}
		List<String> values = redisTemplate.opsForValue().multiGet(keys);
		for(int i=0; i<list.size(); i++){
			int pv = values.get(i)== null? 0 : Integer.parseInt(values.get(i));
			list.get(i).setReadTimes(pv);
		}
		this.fillArticleList(list);
		return list;
	}

	public void fillArticleList(List<Article> list){
		for(Article a : list){
			User user = userService.selectById(a.getUserId());
			a.setAuthor(user.getUsername());
			Channel c = channelService.selectById(a.getChannelId());
			a.addData("channel", c.getName());
		}
		
	}

}
