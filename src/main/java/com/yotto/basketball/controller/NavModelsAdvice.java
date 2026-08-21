package com.yotto.basketball.controller;

import com.yotto.basketball.service.PublicModelService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Exposes the public model list to every view for the Models nav dropdown.
 * Backed by the registry's cached serving plan — no per-request queries.
 */
@ControllerAdvice
public class NavModelsAdvice {

    private final PublicModelService publicModelService;

    public NavModelsAdvice(PublicModelService publicModelService) {
        this.publicModelService = publicModelService;
    }

    @ModelAttribute("navModels")
    public List<PublicModelService.PublicModel> navModels() {
        return publicModelService.list();
    }
}
