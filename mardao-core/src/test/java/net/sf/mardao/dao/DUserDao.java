package net.sf.mardao.dao;

/*
 * #%L
 * mardao-core
 * %%
 * Copyright (C) 2010 - 2014 Wadpam
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import net.sf.mardao.domain.DUser;

/**
 * To test AbstractDao.
 *
 * @author osandstrom Date: 2014-09-03 Time: 20:11
 */
public class DUserDao extends AbstractDao<DUser, Long> {
  public DUserDao(Supplier supplier) {
    super(new DUserMapper(supplier), supplier);
  }

  public Iterable<DUser> queryByDisplayName(String displayName) {
    return queryByField(null, DUserMapper.Field.DISPLAYNAME.getFieldName(), displayName);
  }

  public DUser findByEmail(String email) {
    return queryUniqueByField(null, DUserMapper.Field.EMAIL.getFieldName(), email);
  }
}
