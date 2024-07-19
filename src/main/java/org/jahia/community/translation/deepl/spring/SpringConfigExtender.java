package org.jahia.community.translation.deepl.spring;

import org.apache.commons.lang.StringUtils;
import org.jahia.ajax.gwt.client.widget.toolbar.action.ExecuteActionItem;
import org.jahia.ajax.gwt.client.widget.toolbar.action.TranslateMenuActionItem;
import org.jahia.api.Constants;
import org.jahia.community.translation.deepl.DeeplConstants;
import org.jahia.exceptions.JahiaInitializationException;
import org.jahia.services.JahiaAfterInitializationService;
import org.jahia.services.SpringContextSingleton;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.services.uicomponents.bean.editmode.EditConfiguration;
import org.jahia.services.uicomponents.bean.editmode.SidePanelTab;
import org.jahia.services.uicomponents.bean.toolbar.Item;
import org.jahia.services.uicomponents.bean.toolbar.Menu;
import org.jahia.services.uicomponents.bean.toolbar.Toolbar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;

import javax.jcr.RepositoryException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SpringConfigExtender implements JahiaAfterInitializationService, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(SpringConfigExtender.class);

    final Map<Menu, Set<String>> buttonReferences = new HashMap<>();
    final Map<Toolbar, Set<String>> menuReferences = new HashMap<>();

    @Override
    public void initAfterAllServicesAreStarted() throws JahiaInitializationException {

        final EditConfiguration editmode = getBean("editmode-jahia-anthracite", EditConfiguration.class);
        if (editmode == null) {
            logger.error("Impossible to load the edit mode UI bean");
            return;
        }

        boolean pagesTabFound = false;
        for (SidePanelTab tab : editmode.getTabs()) {
            if ("pages".equals(tab.getKey())) {
                pagesTabFound = true;
                addDeepLMenu(tab.getTreeContextMenu(), "pagesTab");
                break;
            }
        }
        if (!pagesTabFound) logger.error("Impossible to identify the \"pages\" tab");

        addDeepLMenu(editmode.getContextMenu(), "mainContextMenu");
    }

    @Override
    public void destroy() throws Exception {
        buttonReferences.entrySet()
                .forEach(e -> e.getValue().forEach(e.getKey()::removeItem));
        buttonReferences.clear();
        menuReferences.entrySet()
                .forEach(e -> e.getValue().forEach(e.getKey()::removeItem));
        menuReferences.clear();
    }

    private List<String> getServerLanguages() {
        try {
            final JCRSessionWrapper systemSession = JCRSessionFactory.getInstance().getCurrentSystemSession(Constants.LIVE_WORKSPACE, null, null);
            final JCRSiteNode site = JahiaSitesService.getInstance().getSiteByKey(JahiaSitesService.SYSTEM_SITE_KEY, systemSession);
            return site.getLanguages().stream().sorted().collect(Collectors.toList());
        } catch (RepositoryException e) {
            logger.error("", e);
            return Collections.emptyList();
        }
    }

    private String getLanguageLabel(String lang) {
        final Locale locale = new Locale(lang);
        return locale.getDisplayLanguage(locale);
    }

    private void addDeepLMenu(Toolbar contextMenu, String id) {
        int targetIdx = 0;
        for (Item item : contextMenu.getItems()) {
            targetIdx++;
            if (item.getActionItem() instanceof TranslateMenuActionItem) break;
        }

        final Menu deepLMenu = getBean("Toolbar.Item.PagesTab.DeepLMenu", Menu.class);
        deepLMenu.setId("deepl-menu-" + id);
        deepLMenu.setVisibility(new DeepLVisibility());
        contextMenu.addItem(targetIdx, deepLMenu);
        menuReferences.put(contextMenu, Collections.singleton(deepLMenu.getId()));
        final Set<String> buttonKeys = new HashSet<>();
        buttonReferences.put(deepLMenu, buttonKeys);

        getServerLanguages().stream()
                .flatMap(srcLang ->
                        getServerLanguages().stream()
                                .filter(l2 -> !StringUtils.equals(srcLang, l2))
                                .map(targetLang -> {
                                    final Item item = getBean("contextMenu.deeplTranslateItem", Item.class);
                                    item.setId("deeplButton-" + srcLang + "-" + targetLang);
                                    item.setTitle(getLanguageLabel(srcLang) + " -> " + getLanguageLabel(targetLang));
                                    item.setVisibility(new DeepLVisibility(srcLang, targetLang));
                                    final ExecuteActionItem actionItem = (ExecuteActionItem) item.getActionItem();
                                    final Map<String, String> parameters = new HashMap<>();
                                    parameters.put(DeeplConstants.PROP_SUB_TREE, "true");
                                    parameters.put(DeeplConstants.PROP_SRC_LANGUAGE, srcLang);
                                    parameters.put(DeeplConstants.PROP_DEST_LANGUAGE, targetLang);
                                    actionItem.setParameters(parameters);

                                    buttonKeys.add(item.getId());
                                    return item;
                                }))
                .forEach(deepLMenu::addItem);
    }

    private <C> C getBean(String beanID, Class C) {
        return getBean(beanID, C, false);
    }

    private <C> C getBean(String beanID, Class C, boolean coreContextOnly) {
        try {
            final Object bean = coreContextOnly ?
                    SpringContextSingleton.getInstance().getContext().getBean(beanID) :
                    SpringContextSingleton.getBean(beanID);
            if (C.isInstance(bean))
                return (C) bean;
            logger.error(String.format("The bean named %s is not an instance of %s", beanID, C.toString()));
        } catch (BeansException ignored) {
        }
        return null;
    }
}
